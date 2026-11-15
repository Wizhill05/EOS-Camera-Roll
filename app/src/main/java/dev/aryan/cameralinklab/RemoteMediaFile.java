package dev.aryan.cameralinklab;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Bounded, seekable camera access. A server that ignores ranges is never spooled. */
final class RemoteMediaFile implements Closeable {
    private static final int BLOCK=256*1024, CACHE_BLOCKS=8;
    private static final long STARTUP_BYTES=8L*1024*1024, STARTUP_NANOS=12_000_000_000L;
    private final URL url;
    private final CameraHttp.Opener opener;
    private final String user,password;
    private final Consumer<String> log;
    private final Object requestLock=new Object();
    private final Map<Long,byte[]> blocks=new LinkedHashMap<Long,byte[]>(CACHE_BLOCKS,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Long,byte[]> entry){return size()>CACHE_BLOCKS;}
    };
    private volatile boolean closed,playing;
    private volatile long length=-1;
    private CameraHttp active;
    private long startupAt,requested;

    RemoteMediaFile(URL url,CameraHttp.Opener opener,String user,String password,Consumer<String> log){
        this.url=url;this.opener=opener;this.user=user;this.password=password;this.log=log;
    }
    synchronized void prepare()throws IOException {
        ensureOpen();startupAt=System.nanoTime();
        fetch(0,0);
        log.accept("Video byte ranges validated; playback uses bounded on-demand reads.");
    }
    long size(){return length;}
    boolean supportsRanges(){return length>0;}
    void playbackStarted(){playing=true;}

    synchronized int readAt(long position,byte[] buffer,int offset,int count)throws IOException {
        if(position<0||offset<0||count<0||offset>buffer.length-count)throw new IOException("Invalid video read");
        ensureOpen();if(length<0)throw new IOException("Video is not prepared");
        if(count==0)return 0;if(position>=length)return -1;
        long start=position-position%BLOCK;byte[] block=blocks.get(start);
        if(block==null){block=fetch(start,Math.min(length-1,start+BLOCK-1));blocks.put(start,block);}
        int available=(int)Math.min(count,block.length-(position-start));
        System.arraycopy(block,(int)(position-start),buffer,offset,available);return available;
    }
    private byte[] fetch(long start,long end)throws IOException {
        ensureOpen();long size=end-start+1;
        if(!playing&&(requested+size>STARTUP_BYTES||System.nanoTime()-startupAt>STARTUP_NANOS))
            throw new IOException("Video did not start within the streaming limit. Download & play instead.");
        requested+=size;
        CameraHttp client=new CameraHttp(opener,user,password).timeouts(2000,3000);
        synchronized(requestLock){ensureOpen();active=client;}
        HttpURLConnection response=null;
        try{
            Map<String,String> headers=new HashMap<>();headers.put("Range","bytes="+start+"-"+end);headers.put("Connection","close");
            response=client.request(url,headers,true);ensureOpen();
            if(response.getResponseCode()!=206)throw new IOException("Camera does not support seekable streaming. Download & play instead.");
            long[] interval=parseRange(response.getHeaderField("Content-Range"),start,(int)size);
            if(interval[1]!=end)throw new IOException("Camera returned an incomplete video range");
            if(length>=0&&interval[2]!=length)throw new IOException("The video changed during playback");
            byte[] bytes=CameraHttp.readBounded(response.getInputStream(),(int)size);ensureOpen();
            if(bytes.length!=size)throw new IOException("Truncated video byte range");
            length=interval[2];return bytes;
        }finally{
            if(response!=null)response.disconnect();client.close();
            synchronized(requestLock){if(active==client)active=null;}
        }
    }
    static long[] parseRange(String header,long expectedStart,int maximum)throws IOException {
        if(header==null)throw new IOException("Camera omitted Content-Range");
        Matcher m=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(header);
        if(!m.matches())throw new IOException("Invalid camera byte-range header");
        try{
            long start=Long.parseLong(m.group(1)),end=Long.parseLong(m.group(2)),total=Long.parseLong(m.group(3));
            if(start!=expectedStart||end<start||end>=total||end-start>=maximum)throw new IOException("Camera returned the wrong video range");
            return new long[]{start,end,total};
        }catch(NumberFormatException e){throw new IOException("Invalid video range size",e);}
    }
    private void ensureOpen()throws IOException{if(closed)throw new IOException("Video closed");}
    @Override public void close(){
        synchronized(requestLock){closed=true;if(active!=null)active.cancel();}
    }
}
