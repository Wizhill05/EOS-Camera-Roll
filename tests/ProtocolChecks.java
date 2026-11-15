package dev.aryan.cameralinklab;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.net.SocketFactory;
import com.sun.net.httpserver.HttpServer;

public final class ProtocolChecks {
    private static int checks;
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    interface Checked {void run() throws Exception;}
    private static void rejects(Checked call,String name)throws Exception{try{call.run();throw new AssertionError("Expected rejection: "+name);}catch(IOException expected){check(true,name);}}
    public static void main(String[] args)throws Exception{
        ptpSession();ptpRejected();ptpCancel();badFrame();http();authDiagnostics();
        rejects(()->CameraHttp.url("ftp://camera/ccapi"),"Reject non-HTTP URL");
        rejects(()->CameraHttp.url("http://user:secret@camera/ccapi"),"Keep credentials out of URLs");
        check(!CameraHttp.sameOrigin(new URL("http://camera:8080/ccapi"),new URL("http://other:8080/ccapi")),"Reject cross-origin links");
        System.out.println("All "+checks+" protocol checks passed.");
    }
    private static void ptpSession()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();List<String> log=new CopyOnWriteArrayList<>();
        try(ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            Future<?> fake=executor.submit(()->{try(Socket command=server.accept()){
                command.setSoTimeout(3000);
                PtpIp.Frame init=PtpIp.read(command);
                if(init.type!=1||PtpIp.wrap(Arrays.copyOfRange(init.body,init.body.length-4,init.body.length)).getInt()!=0x10000)throw new AssertionError("init encoding");
                byte[] ack=PtpIp.le(26).putInt(42).put(new byte[16]).putShort((short)0).putInt(0x10000).array();fragment(command,2,ack);
                try(Socket event=server.accept()){
                    event.setSoTimeout(3000);PtpIp.Frame e=PtpIp.read(event);
                    if(e.type!=3||PtpIp.wrap(e.body).getInt()!=42)throw new AssertionError("event connection id");
                    PtpIp.write(event,4,new byte[0]);PtpIp.write(event,13,new byte[0]);
                    if(PtpIp.read(event).type!=14)throw new AssertionError("event ping/pong");
                    request(command,0x1002,0);response(command,0,0x2001);
                    request(command,0x1001,1);dataset(command,1,device());response(command,1,0x2001);
                    request(command,0x1004,2);dataset(command,2,PtpIp.le(8).putInt(1).putInt(0x10001).array());response(command,2,0x2001);
                    request(command,0x1003,3);response(command,3,0x2001);
                }
            }catch(Exception error){throw new RuntimeException(error);}});
            PtpIp client=new PtpIp(SocketFactory.getDefault(),log::add);
            try{client.connect("127.0.0.1",server.getLocalPort(),new byte[16]);client.diagnose();}finally{client.finish();}
            fake.get(5,TimeUnit.SECONDS);
            check(log.stream().anyMatch(x->x.contains("Canon / EOS R50 V / firmware 1.2.0")),"Decode actual fragmented PTP/IP device data");
            check(log.stream().anyMatch(x->x.contains("1 storage IDs")),"Read storage IDs through a session");
            check(log.stream().anyMatch(x->x.equals("SESSION: closed")),"Close session after diagnostics");
        }finally{executor.shutdownNow();}
    }
    private static void request(Socket s,int opcode,int transaction)throws Exception{
        PtpIp.Frame f=PtpIp.read(s);ByteBuffer b=PtpIp.wrap(f.body);
        if(f.type!=6||b.getInt()!=1||Short.toUnsignedInt(b.getShort())!=opcode||b.getInt()!=transaction)throw new AssertionError("Unexpected operation");
    }
    private static void response(Socket s,int tx,int code)throws Exception{PtpIp.write(s,7,PtpIp.le(6).putShort((short)code).putInt(tx).array());}
    private static void dataset(Socket s,int tx,byte[] data)throws Exception{
        PtpIp.write(s,9,PtpIp.le(12).putInt(tx).putLong(data.length).array());int split=data.length/2;
        fragment(s,10,PtpIp.le(4+split).putInt(tx).put(data,0,split).array());
        PtpIp.write(s,12,PtpIp.le(4+data.length-split).putInt(tx).put(data,split,data.length-split).array());
    }
    private static byte[] device()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(PtpIp.le(8).putShort((short)100).putInt(11).putShort((short)100).array());str(out,"Canon");out.write(new byte[2]);
        for(int i=0;i<5;i++)out.write(PtpIp.le(4).putInt(0).array());str(out,"Canon");str(out,"EOS R50 V");str(out,"1.2.0");str(out,"test");return out.toByteArray();
    }
    private static void str(OutputStream out,String s)throws Exception{out.write(s.length()+1);out.write((s+"\0").getBytes(StandardCharsets.UTF_16LE));}
    private static void fragment(Socket s,int type,byte[] body)throws Exception{
        byte[] frame=PtpIp.le(body.length+8).putInt(body.length+8).putInt(type).put(body).array();
        for(byte b:frame)s.getOutputStream().write(b);s.getOutputStream().flush();
    }
    private static void ptpRejected()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0)){
            Future<?> fake=executor.submit(()->{try(Socket s=server.accept()){PtpIp.read(s);PtpIp.write(s,5,PtpIp.le(4).putInt(1).array());}catch(Exception e){throw new RuntimeException(e);}});
            try(PtpIp client=new PtpIp(SocketFactory.getDefault(),x->{})){rejects(()->client.connect("127.0.0.1",server.getLocalPort(),new byte[16]),"Report camera pairing rejection");}fake.get(3,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
    private static void ptpCancel()throws Exception{
        ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch waiting=new CountDownLatch(1);
        try(ServerSocket server=new ServerSocket(0);PtpIp client=new PtpIp(SocketFactory.getDefault(),x->{})){
            Future<?> fake=executor.submit(()->{try(Socket s=server.accept()){PtpIp.read(s);waiting.countDown();s.getInputStream().read();}catch(Exception e){throw new RuntimeException(e);}});
            Future<?> connection=executor.submit(()->{try{client.connect("127.0.0.1",server.getLocalPort(),new byte[16]);throw new AssertionError("Cancelled connect succeeded");}catch(IOException expected){}});
            if(!waiting.await(3,TimeUnit.SECONDS))throw new AssertionError("No initialization");client.close();connection.get(2,TimeUnit.SECONDS);fake.get(2,TimeUnit.SECONDS);check(true,"Cancel blocked pairing without waiting 90 seconds");
        }finally{executor.shutdownNow();}
    }
    private static void badFrame()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0)){
            Future<?> fake=executor.submit(()->{try(Socket s=server.accept()){s.getOutputStream().write(PtpIp.le(8).putInt(-1).putInt(2).array());}catch(IOException e){throw new RuntimeException(e);}});
            try(Socket client=new Socket("127.0.0.1",server.getLocalPort())){rejects(()->PtpIp.read(client),"Reject oversized frame before allocation");}fake.get(3,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
    private static void http()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ccapi",e->{byte[] b="{\"ver100\":[]}".getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});
        server.createContext("/ccapi/auth",e->{String auth=e.getRequestHeaders().getFirst("Authorization");if(!"Basic dXNlcjpwYXNz".equals(auth)){e.getResponseHeaders().add("WWW-Authenticate","Basic realm=\"camera\"");e.sendResponseHeaders(401,-1);}else{e.sendResponseHeaders(200,2);e.getResponseBody().write(new byte[]{'{','}'});}e.close();});
        server.createContext("/ccapi/redirect",e->{e.getResponseHeaders().add("Location","/ccapi");e.sendResponseHeaders(302,-1);e.close();});server.start();
        try(CameraHttp client=new CameraHttp(URL::openConnection,"user","pass")){
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            HttpURLConnection c=client.get(CameraHttp.url(base+"/ccapi"));check(CameraHttp.readBounded(c.getInputStream(),100).length>0,"Read CCAPI API-map response");c.disconnect();
            c=client.get(CameraHttp.url(base+"/ccapi/auth"));check(c.getResponseCode()==200,"Challenge-based HTTP authentication");c.disconnect();
            rejects(()->client.get(CameraHttp.url(base+"/ccapi/redirect")),"Never follow a redirect with credentials");
            rejects(()->CameraHttp.readBounded(new ByteArrayInputStream(new byte[101]),100),"Bound diagnostic response memory");
        }finally{server.stop(0);}
        String challenge="Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"";
        String auth=CameraHttp.authorization(challenge,new URL("http://camera/dir/index.html"),"Mufasa","Circle Of Life");
        Matcher nonce=Pattern.compile("cnonce=\"([^\"]+)\"").matcher(auth);if(!nonce.find())throw new AssertionError("No cnonce");
        String expected=md5("939e7578ed9e3c518a452acee763bce9:dcd98b7102dd2f0e8b11d0f600bfb0c093:00000001:"+nonce.group(1)+":auth:39aff3a2bab6126f332b942af96d3366");
        check(auth.contains("response=\""+expected+"\""),"Digest matches independent RFC example calculation");
    }
    private static String md5(String s)throws Exception{return CameraHttp.hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));}

    private static String authField(String auth,String name){
        if(auth==null)return "";
        Matcher m=Pattern.compile("(?:^|[, ])"+name+"=\"([^\"]*)\"").matcher(auth);
        return m.find()?m.group(1):"";
    }

    private static void authDiagnostics()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        List<String> trace=new CopyOnWriteArrayList<>();
        String challenge="Digest realm=\"camera\", nonce=\"sensitive-nonce\", algorithm=MD5, qop=\"auth\"";
        server.createContext("/ccapi/digest",exchange->{
            try{
                String header=exchange.getRequestHeaders().getFirst("Authorization");
                String cnonce=authField(header,"cnonce");
                String expected=md5(md5("sensitive-user:camera:sensitive-password")+":sensitive-nonce:00000001:"+cnonce+":auth:"+md5("GET:/ccapi/digest"));
                if(!cnonce.isEmpty()&&expected.equals(authField(header,"response"))&&"/ccapi/digest".equals(authField(header,"uri"))){
                    exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
                }else{exchange.getResponseHeaders().add("WWW-Authenticate",challenge);exchange.sendResponseHeaders(401,-1);}
            }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}
        });
        server.createContext("/ccapi/rejected",exchange->{exchange.getResponseHeaders().add("WWW-Authenticate",challenge);exchange.sendResponseHeaders(401,-1);exchange.close();});
        server.start();
        try(CameraHttp client=new CameraHttp(URL::openConnection,"sensitive-user","sensitive-password",trace::add)){
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            HttpURLConnection c=client.get(CameraHttp.url(base+"/ccapi/digest"));
            check(c.getResponseCode()==200,"Complete Digest challenge-response over HTTP");c.disconnect();
            check(trace.stream().anyMatch(s->s.equals("HTTP: initial response 401"))&&trace.stream().anyMatch(s->s.equals("AUTH: authenticated response 200")),"Distinguish initial challenge from authenticated success");
            rejects(()->client.get(CameraHttp.url(base+"/ccapi/rejected")),"Expose rejection after credentials were sent");
            check(trace.stream().anyMatch(s->s.equals("AUTH: authenticated response 401")),"Retain authenticated rejection stage in diagnostic log");
            check(trace.stream().noneMatch(s->s.contains("sensitive-user")||s.contains("sensitive-password")||s.contains("sensitive-nonce")),"Never log credentials, realm contents or nonce values");
        }finally{server.stop(0);}
        check(CameraHttp.challengeSummary("Digest realm=\"private account\", nonce=\"private nonce\", algorithm=SHA-256, qop=\"auth\", stale=true").equals("scheme=Digest, algorithm=SHA-256, qop=auth available, realm present=true, nonce present=true, stale=true"),"Summarize authentication parameters without secret values");
    }
}
