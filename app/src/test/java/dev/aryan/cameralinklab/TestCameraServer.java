package dev.aryan.cameralinklab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

final class TestCameraServer implements AutoCloseable {
    private final ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final byte[] original;
    volatile byte[] thumbnail;
    volatile boolean emptyCatalog;
    final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
    TestCameraServer(byte[] original)throws IOException{this.original=original;workers.execute(()->{while(!socket.isClosed())try{Socket request=socket.accept();workers.execute(()->respond(request));}catch(IOException closed){break;}});}
    String origin(){return "http://127.0.0.1:"+socket.getLocalPort();}
    private void respond(Socket request){try(Socket connection=request){connection.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));String first=in.readLine();if(first==null)return;requests.incrementAndGet();String line;while((line=in.readLine())!=null&&!line.isEmpty()){}
        boolean info=first.contains("kind=info");byte[] body=info?("{\"filesize\":"+original.length+",\"lastmodifieddate\":\"2026-11-15\"}").getBytes(StandardCharsets.UTF_8):original;
        if(first.contains("kind=thumbnail")&&thumbnail!=null)body=thumbnail;
        if(emptyCatalog)body=(first.startsWith("GET /ccapi ")?"{\"ver140\":[{\"url\":\"contents\",\"get\":true}]}":"{\"path\":[]}").getBytes(StandardCharsets.UTF_8);
        OutputStream out=connection.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+(info?"application/json":"image/jpeg")+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }catch(IOException ignored){}}
    @Override public void close()throws IOException{socket.close();workers.shutdownNow();}
}
