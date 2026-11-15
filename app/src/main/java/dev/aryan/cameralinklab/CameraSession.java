package dev.aryan.cameralinklab;

import android.net.Network;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.*;
import org.json.*;

final class CameraSession implements AutoCloseable, MediaCatalog.Transport {
    final URL root;
    final Network network;
    final String user, password;
    volatile String identity;
    final Consumer<String> log;
    final Set<CameraHttp> clients=new CopyOnWriteArraySet<>();
    volatile boolean closed;
    CameraSession(URL url,Network network,String user,String password,Consumer<String> log)throws IOException {
        root=CameraHttp.url(new URL(url.getProtocol(),url.getHost(),url.getPort(),"/ccapi").toString());
        this.network=network;this.user=user;this.password=password;this.log=log;identity="address:"+root.getHost();
    }
    @Override public void cameraIdentity(String identity){this.identity=identity;}
    String key(MediaItem item){return item.keyFor(identity);}
    CameraHttp.Opener opener(){return url->network==null?url.openConnection():network.openConnection(url);}
    CameraHttp client()throws IOException {
        if(closed)throw new IOException("Camera disconnected");
        CameraHttp client=new CameraHttp(opener(),user,password,log);clients.add(client);
        if(closed){client.close();clients.remove(client);throw new IOException("Camera disconnected");}return client;
    }
    @Override public synchronized Object json(URL url)throws IOException {
        byte[] data=bytes(url,4*1024*1024);
        try{Object value=new JSONTokener(new String(data,java.nio.charset.StandardCharsets.UTF_8)).nextValue();
            if(!(value instanceof JSONObject)&&!(value instanceof JSONArray))throw new IOException("Unexpected camera response");return value;
        }catch(JSONException e){throw new IOException("Camera returned invalid JSON",e);}
    }
    synchronized byte[] bytes(URL url,int maximum)throws IOException {
        if(!CameraHttp.sameOrigin(root,url))throw new IOException("Invalid camera origin");
        CameraHttp client=client();
        try{HttpURLConnection c=client.get(url);try(InputStream in=c.getInputStream()){return CameraHttp.readBounded(in,maximum);}finally{c.disconnect();}}
        finally{clients.remove(client);client.close();}
    }
    SaveResult save(MediaItem item,OutputStream out,BooleanSupplier cancelled,BiConsumer<Long,Long> progress)throws IOException {
        if(!CameraHttp.sameOrigin(root,item.url))throw new IOException("Invalid camera origin");
        CameraHttp client=client();
        try{
            HttpURLConnection c=client.get(item.url);
            try(InputStream in=c.getInputStream()){
                MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException(e);}
                long expected=c.getContentLengthLong(),count=0,last=0;byte[] buffer=new byte[65536];int n;
                while((n=in.read(buffer))!=-1){if(cancelled.getAsBoolean()||closed)throw new IOException("Download cancelled");out.write(buffer,0,n);digest.update(buffer,0,n);count+=n;
                    if(System.nanoTime()-last>400_000_000){progress.accept(count,expected);last=System.nanoTime();}}
                if(expected>=0&&count!=expected)throw new IOException("Download incomplete: "+count+" of "+expected+" bytes");
                out.flush();progress.accept(count,expected);return new SaveResult(count,CameraHttp.hex(digest.digest()));
            }finally{c.disconnect();}
        }finally{clients.remove(client);client.close();}
    }
    void cancelRequests(){for(CameraHttp c:clients)c.close();}
    @Override public void close(){closed=true;cancelRequests();}
    static final class SaveResult{final long bytes;final String hash;SaveResult(long bytes,String hash){this.bytes=bytes;this.hash=hash;}}
}
