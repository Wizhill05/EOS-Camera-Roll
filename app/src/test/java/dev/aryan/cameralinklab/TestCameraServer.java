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
    volatile int rotation=-1;
    volatile java.util.concurrent.CountDownLatch thumbnailGate,infoGate,displayGate;
    final java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger(),maximumActive=new java.util.concurrent.atomic.AtomicInteger();
    final java.util.List<String> targets=new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean emptyCatalog,poisonOnRange,poisoned,failThumbnails;
    final java.util.List<String> ranges=new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile java.util.List<String> catalogPaths;
    final java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
    TestCameraServer(byte[] original)throws IOException{this.original=original;workers.execute(()->{while(!socket.isClosed())try{Socket request=socket.accept();workers.execute(()->respond(request));}catch(IOException closed){break;}});}
    String origin(){return "http://127.0.0.1:"+socket.getLocalPort();}
    private void respond(Socket request){int now=active.incrementAndGet();maximumActive.accumulateAndGet(now,Math::max);try(Socket connection=request){connection.setSoTimeout(5000);BufferedReader in=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));String first=in.readLine();if(first==null)return;requests.incrementAndGet();targets.add(first.split(" ")[1]);String range=null,line;while((line=in.readLine())!=null&&!line.isEmpty()){if(line.regionMatches(true,0,"Range: ",0,7))range=line.substring(7);}
        boolean alreadyPoisoned=poisoned;if(range!=null){ranges.add(range);if(poisonOnRange)poisoned=true;}
        boolean info=first.contains("kind=info");if(first.contains("kind=display")&&displayGate!=null)displayGate.await(5,TimeUnit.SECONDS);if(info&&infoGate!=null)infoGate.await(5,TimeUnit.SECONDS);if(first.contains("kind=thumbnail")&&thumbnailGate!=null)thumbnailGate.await(5,TimeUnit.SECONDS);byte[] body=info?("{\"filesize\":"+original.length+",\"lastmodifieddate\":\"2026-11-15\""+(rotation<0?"":",\"rotate\":"+rotation)+"}").getBytes(StandardCharsets.UTF_8):original;
        if(first.contains("kind=thumbnail")&&thumbnail!=null)body=thumbnail;
        if(emptyCatalog)body=(first.startsWith("GET /ccapi ")?"{\"ver140\":[{\"url\":\"contents\",\"get\":true}]}":"{\"path\":[]}").getBytes(StandardCharsets.UTF_8);
        if(catalogPaths!=null){String path=first.split(" ")[1];if(path.equals("/ccapi"))body="{\"ver140\":[{\"url\":\"contents\",\"get\":true}]}".getBytes(StandardCharsets.UTF_8);else if(path.equals("/ccapi/ver140/contents?kind=number"))body="{\"pagenumber\":0}".getBytes(StandardCharsets.UTF_8);else if(path.equals("/ccapi/ver140/contents"))body=new org.json.JSONObject(java.util.Collections.singletonMap("path",catalogPaths)).toString().getBytes(StandardCharsets.UTF_8);}
        if(first.startsWith("GET /ccapi/health "))body="{}".getBytes(StandardCharsets.UTF_8);
        int start=0,end=body.length-1;String status="200 OK",rangeHeader="";
        if(alreadyPoisoned||failThumbnails&&first.contains("kind=thumbnail")){status="503 Service Unavailable";body=new byte[0];end=-1;}
        OutputStream out=connection.getOutputStream();out.write(("HTTP/1.1 "+status+"\r\n"+rangeHeader+"Content-Type: "+(info?"application/json":"image/jpeg")+"\r\nContent-Length: "+(end-start+1)+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body,start,end-start+1);out.flush();
    }catch(IOException ignored){}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{active.decrementAndGet();}}
    @Override public void close()throws IOException{socket.close();workers.shutdownNow();}
}
