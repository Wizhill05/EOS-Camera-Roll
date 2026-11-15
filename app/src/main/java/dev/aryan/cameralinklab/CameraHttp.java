package dev.aryan.cameralinklab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.function.Consumer;

final class CameraHttp implements AutoCloseable {
    interface Opener { URLConnection open(URL url) throws IOException; }
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

    HttpURLConnection get(URL url) throws IOException {
        return request(url, Collections.emptyMap(), false);
    }

    HttpURLConnection request(URL url, Map<String,String> headers, boolean allowPartial) throws IOException {
        trace.accept("REQUEST: GET " + url.getPath());
        HttpURLConnection c = open(url, null, headers);
        int status = c.getResponseCode();
        trace.accept("HTTP: initial response " + status);
        if (status == 401) {
            String challenge = c.getHeaderField("WWW-Authenticate");
            trace.accept("AUTH: " + challengeSummary(challenge));
            trace.accept("AUTH: username supplied=" + !username.isEmpty() + ", password supplied=" + !password.isEmpty());
            c.disconnect();
            if (username.isEmpty()) throw new IOException("HTTP 401 before authentication: enter the CCAPI account in the app. No authenticated request was sent.");
            String authorization = authorization(challenge, url, username, password);
            trace.accept("AUTH: sending " + (authorization.startsWith("Digest") ? "Digest" : "Basic") + " response. Credentials and authorization header are excluded from this log.");
            c = open(url, authorization, headers);
            status = c.getResponseCode();
            trace.accept("AUTH: authenticated response " + status);
            if (status == 401) trace.accept("AUTH: rejection challenge: " + challengeSummary(c.getHeaderField("WWW-Authenticate")));
        }
        if (status != 200 && !(allowPartial && status == 206)) {
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

    private HttpURLConnection open(URL url, String authorization, Map<String,String> headers) throws IOException {
        if (cancelled) throw new IOException("Cancelled");
        HttpURLConnection c = (HttpURLConnection)opener.open(url);
        active = c;
        if (cancelled) { c.disconnect(); throw new IOException("Cancelled"); }
        c.setConnectTimeout(connectTimeout); c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(false); c.setUseCaches(false);
        c.setRequestMethod("GET"); c.setRequestProperty("Accept-Encoding", "identity");
        for (Map.Entry<String,String> header : headers.entrySet()) c.setRequestProperty(header.getKey(), header.getValue());
        if (authorization != null) c.setRequestProperty("Authorization", authorization);
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

    static String authorization(String challenge, URL url, String user, String pass) throws IOException {
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
        String a2=hash(hashAlg,"GET:"+uri);
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
    @Override public void close() { cancelled=true; HttpURLConnection c=active;if(c!=null)c.disconnect(); }
}
