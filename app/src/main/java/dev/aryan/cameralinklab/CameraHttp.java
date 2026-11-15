package dev.aryan.cameralinklab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class CameraHttp implements AutoCloseable {
    interface Opener { URLConnection open(URL url) throws IOException; }
    private static final ExecutorService cancellations=Executors.newCachedThreadPool(task->{Thread thread=new Thread(task,"camera-disconnect");thread.setDaemon(true);return thread;});
    private static final ConcurrentMap<String,Semaphore> cameras=new ConcurrentHashMap<>();
    private static final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"camera-deadline");thread.setDaemon(true);return thread;});
    private final Object state=new Object();
    private Semaphore camera;
    private String origin;
    private ScheduledFuture<?> deadlineTask;
    private long deadlineMillis,started,received;
    private volatile boolean expired;
    private final Opener opener;
    private final String username, password;
    private final Consumer<String> trace;
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    private int connectTimeout=8000,readTimeout=20000;

    CameraHttp(Opener opener, String username, String password) {
        this(opener, username, password, ignored -> {});
    }

    CameraHttp(Opener opener, String username, String password, Consumer<String> trace) {
        this.opener = opener; this.username = username; this.password = password;
        this.trace = trace;
    }

    CameraHttp timeouts(int connect,int read){if(connect<=0||read<=0)throw new IllegalArgumentException("Timeout must be positive");connectTimeout=connect;readTimeout=read;return this;}

    CameraHttp deadline(long millis){if(millis<=0)throw new IllegalArgumentException("Deadline must be positive");deadlineMillis=millis;return this;}

    static URL url(String text) throws IOException {
        URL u = new URL(text.trim());
        if (!(u.getProtocol().equals("http") || u.getProtocol().equals("https")) || u.getHost().isEmpty()
                || u.getUserInfo() != null || u.getRef() != null || !u.getPath().startsWith("/ccapi"))
            throw new IOException("Enter the camera URL, such as http://192.168.0.1:8080/ccapi. Put account details in the account fields.");
        return u;
    }

    static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equalsIgnoreCase(b.getProtocol()) && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }
    private static int port(URL u) { return u.getPort() < 0 ? u.getDefaultPort() : u.getPort(); }

    private String method="GET";
    private byte[] payload;
    HttpURLConnection put(URL url,byte[] json)throws IOException{method="PUT";payload=json;return get(url);}
    HttpURLConnection get(URL url) throws IOException {
        checkRunning();
        if(started==0){started=System.nanoTime();if(deadlineMillis>0)deadlineTask=deadlines.schedule(()->{expired=true;cancel();},deadlineMillis,TimeUnit.MILLISECONDS);}
        acquire(url);
        String query=url.getQuery();
        trace.accept("REQUEST: "+method+" " + url.getPath() + (query!=null&&query.matches("kind=(thumbnail|display|info|number)|page=[0-9]+")?" ["+query+"]":""));
        HttpURLConnection c = open(url, null);
        int status = c.getResponseCode();
        ensureActive(c);
        trace.accept("HTTP: initial response " + status);
        if (status == 401) {
            String challenge = c.getHeaderField("WWW-Authenticate");
            trace.accept("AUTH: " + challengeSummary(challenge));
            trace.accept("AUTH: username supplied=" + !username.isEmpty() + ", password supplied=" + !password.isEmpty());
            c.disconnect();
            if (username.isEmpty()) throw new IOException("HTTP 401 before authentication: enter the CCAPI account in the app. No authenticated request was sent.");
            String authorization = authorization(challenge, url, username, password,method);
            trace.accept("AUTH: sending " + (authorization.startsWith("Digest") ? "Digest" : "Basic") + " response. Credentials and authorization header are excluded from this log.");
            c = open(url, authorization);
            status = c.getResponseCode();
            ensureActive(c);
            trace.accept("AUTH: authenticated response " + status);
            if (status == 401) trace.accept("AUTH: rejection challenge: " + challengeSummary(c.getHeaderField("WWW-Authenticate")));
        }
        if (status != 200 && !(method.equals("PUT") && status==204)) {
            c.disconnect();
            if (status == 401) throw new IOException("HTTP 401 after the app sent an authentication response. This does not distinguish incorrect credentials from an authentication compatibility issue. Share the AUTH log lines.");
            if (status >= 300 && status < 400) throw new IOException("HTTP " + status + ": redirect refused. Enter the exact camera URL.");
            throw new StatusException(status, "Camera returned HTTP " + status + ". Check the connection and camera mode.");
        }
        return c;
    }

    static String challengeSummary(String challenge) {
        if (challenge == null) return "WWW-Authenticate header missing";
        String trimmed = challenge.trim();
        if (trimmed.regionMatches(true, 0, "Basic ", 0, 6)) return "scheme=Basic";
        if (!trimmed.regionMatches(true, 0, "Digest ", 0, 7)) return "unrecognized authentication scheme";
        Map<String,String> fields = digestFields(trimmed.substring(7));
        String algorithm = fields.getOrDefault("algorithm", "MD5").toUpperCase(Locale.ROOT);
        if (!Arrays.asList("MD5", "MD5-SESS", "SHA-256", "SHA-256-SESS", "SHA-512-256", "SHA-512-256-SESS").contains(algorithm)) algorithm = "unsupported";
        String qop = fields.get("qop");
        String qopSummary = qop == null ? "absent" : Arrays.stream(qop.split(",")).anyMatch(v -> v.trim().equalsIgnoreCase("auth")) ? "auth available" : "auth unavailable";
        return "scheme=Digest, algorithm=" + algorithm + ", qop=" + qopSummary
                + ", realm present=" + fields.containsKey("realm") + ", nonce present=" + fields.containsKey("nonce")
                + ", stale=" + "true".equalsIgnoreCase(fields.get("stale"));
    }

    private static Map<String,String> digestFields(String challenge) {
        Map<String,String> fields = new HashMap<>();
        Matcher m = Pattern.compile("([a-zA-Z]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))").matcher(challenge);
        while (m.find()) fields.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2) == null ? m.group(3) : m.group(2));
        return fields;
    }

    static final class StatusException extends IOException {
        final int status;
        StatusException(int status, String message) { super(message); this.status=status; }
    }

    private HttpURLConnection open(URL url, String authorization) throws IOException {
        checkRunning();
        HttpURLConnection c = (HttpURLConnection)opener.open(url);
        active = c;
        if (cancelled) { c.disconnect(); checkRunning(); }
        c.setConnectTimeout(connectTimeout); c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(false); c.setUseCaches(false);
        c.setRequestMethod(method); c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Connection", "close");
        if (authorization != null) c.setRequestProperty("Authorization", authorization);
        if(payload!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(payload.length);try(OutputStream out=c.getOutputStream()){out.write(payload);}}
        return c;
    }

    static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) {
            if (out.size() + n > max) throw new IOException("Response exceeds " + max + " bytes; use Save original for media files.");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    static String authorization(String challenge, URL url, String user, String pass)throws IOException{return authorization(challenge,url,user,pass,"GET");}
    static String authorization(String challenge, URL url, String user, String pass,String method) throws IOException {
        if (challenge == null) throw new IOException("Camera sent no authentication challenge");
        if (user.contains("\r") || user.contains("\n") || user.contains(":")) throw new IOException("Invalid account username");
        if (challenge.regionMatches(true,0,"Basic",0,5)) {
            return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        }
        if (!challenge.regionMatches(true,0,"Digest",0,6)) throw new IOException("Unsupported camera authentication scheme");
        Map<String,String> fields = digestFields(challenge.substring(6));
        String realm=fields.get("realm"), nonce=fields.get("nonce"), alg=fields.getOrDefault("algorithm","MD5");
        if (realm == null || nonce == null) throw new IOException("Incomplete Digest challenge");
        boolean sess = alg.toLowerCase(Locale.ROOT).endsWith("-sess");
        String hashAlg = sess ? alg.substring(0,alg.length()-5) : alg;
        if (!hashAlg.equalsIgnoreCase("MD5") && !hashAlg.equalsIgnoreCase("SHA-256")) throw new IOException("Unsupported Digest algorithm " + alg);
        String qop=fields.get("qop");
        if (qop != null && Arrays.stream(qop.split(",")).noneMatch(v -> v.trim().equalsIgnoreCase("auth")))
            throw new IOException("Camera requests unsupported Digest quality of protection");
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
        String cnonce=hex(random), uri=url.getFile().isEmpty()?"/":url.getFile();
        String a1=hash(hashAlg,user+":"+realm+":"+pass);
        if(sess) a1=hash(hashAlg,a1+":"+nonce+":"+cnonce);
        String a2=hash(hashAlg,method+":"+uri);
        String response=hash(hashAlg,a1+":"+nonce+":"+(qop==null?"":"00000001:"+cnonce+":auth:")+a2);
        String result="Digest username="+quote(user)+", realm="+quote(realm)+", nonce="+quote(nonce)+", uri="+quote(uri)+", response="+quote(response)+", algorithm="+alg;
        if(qop!=null) result+=", qop=auth, nc=00000001, cnonce="+quote(cnonce);
        else if(sess) result+=", cnonce="+quote(cnonce);
        if(fields.containsKey("opaque")) result+=", opaque="+quote(fields.get("opaque"));
        return result;
    }

    private static String quote(String s) throws IOException {
        if(s.contains("\r")||s.contains("\n")) throw new IOException("Invalid authentication field");
        return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";
    }
    private static String hash(String alg, String s) throws IOException {
        try { return hex(MessageDigest.getInstance(alg).digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte v:b)s.append(String.format(Locale.US,"%02x",v & 255));return s.toString(); }
    private void acquire(URL url)throws IOException {
        String key=url.getProtocol().toLowerCase(Locale.ROOT)+"://"+url.getHost().toLowerCase(Locale.ROOT)+":"+port(url);
        synchronized(state){if(camera!=null){if(!key.equals(origin))throw new IOException("Cannot reuse a camera connection across servers");checkRunning();return;}}
        Semaphore next=cameras.computeIfAbsent(key,ignored->new Semaphore(1,true));long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(connectTimeout);
        try{
            while(true){checkRunning();if(System.nanoTime()>=until)throw new BusyException();
                if(next.tryAcquire(50,TimeUnit.MILLISECONDS)){
                    synchronized(state){if(cancelled){next.release();checkRunning();}camera=next;origin=key;}return;
                }
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("Camera request cancelled");}
    }
    private void checkRunning()throws IOException{
        if(expired)throw new SocketTimeoutException("Camera request exceeded its total time limit. Background loading stopped; refresh to retry.");
        if(cancelled)throw new InterruptedIOException("Camera request cancelled");
    }
    private void ensureActive(HttpURLConnection connection)throws IOException{if(cancelled){connection.disconnect();checkRunning();}}
    InputStream body()throws IOException{
        checkRunning();HttpURLConnection connection=active;if(connection==null)throw new IOException("No camera response");
        return new FilterInputStream(connection.getInputStream()){
            @Override public int read(byte[] bytes,int offset,int count)throws IOException{
                checkRunning();try{int n=in.read(bytes,offset,count);checkRunning();if(n>0)received+=n;return n;}catch(IOException error){checkRunning();throw error;}
            }
            @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
        };
    }
    byte[] read(int maximum)throws IOException{
        checkRunning();if(active.getContentLengthLong()>maximum)throw new ResponseTooLargeException();
        try(InputStream in=body()){
            try{return readBounded(in,maximum);}catch(IOException error){if(error.getMessage()!=null&&error.getMessage().startsWith("Response exceeds"))throw new ResponseTooLargeException();throw error;}
        }
    }
    static final class BusyException extends SocketTimeoutException{BusyException(){super("The previous camera request is still finishing. Wait, then refresh.");}}
    static final class ResponseTooLargeException extends IOException{ResponseTooLargeException(){super("Camera returned an oversized preview. Background loading stopped to avoid fetching originals.");}}
    void cancel(){cancelled=true;HttpURLConnection connection=active;if(connection!=null)cancellations.execute(connection::disconnect);}
    @Override public void close(){
        cancelled=true;if(deadlineTask!=null)deadlineTask.cancel(false);
        try{HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
        finally{synchronized(state){if(camera!=null){camera.release();camera=null;trace.accept("HTTP: closed; "+received+" bytes; "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+" ms"+(expired?"; deadline":received==0?"; no body read":""));}}}
    }
}
