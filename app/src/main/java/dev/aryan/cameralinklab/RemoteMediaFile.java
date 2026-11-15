package dev.aryan.cameralinklab;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Seekable byte access when the camera supports Range; sequential disk buffering otherwise. */
final class RemoteMediaFile implements Closeable {
    private static final int BLOCK=256*1024;
    private final URL url;
    private final CameraHttp.Opener opener;
    private final String user,password;
    private final Consumer<String> log;
    private final File temporary;
    private final Object monitor=new Object();
    private volatile boolean closed;
    private volatile IOException failure;
    private volatile CameraHttp active;
    private volatile long length=-1,available;
    private volatile boolean complete;
    private boolean ranges;
    private long blockStart=-1;
    private byte[] block;
    private RandomAccessFile reader;

    RemoteMediaFile(URL url,CameraHttp.Opener opener,String user,String password,File temporary,Consumer<String> log){
        this.url=url;this.opener=opener;this.user=user;this.password=password;this.temporary=temporary;this.log=log;
    }
    void prepare()throws IOException {
        CameraHttp client=new CameraHttp(opener,user,password);active=client;
        HttpURLConnection connection=null;
        try{
            ensureOpen();connection=client.request(url,Collections.singletonMap("Range","bytes=0-"+(BLOCK-1)),true);
            if(connection.getResponseCode()==206){
                long[] interval=parseRange(connection.getHeaderField("Content-Range"),0,BLOCK);
                length=interval[2];block=CameraHttp.readBounded(connection.getInputStream(),BLOCK);
                if(block.length!=interval[1]+1)throw new IOException("Camera returned an incomplete video range");
                blockStart=0;ranges=true;log.accept("Video byte ranges supported; seeking is available.");
                connection.disconnect();client.close();active=null;
            }else{
                length=connection.getContentLengthLong();
                if(length>0&&length>temporary.getParentFile().getUsableSpace()-64*1024*1024L)
                    throw new IOException("Not enough phone storage to buffer this video. The camera does not support byte-range playback.");
                reader=new RandomAccessFile(temporary,"rw");
                HttpURLConnection response=connection;
                log.accept("Camera ignored byte ranges. Buffering video to temporary storage; seeking may wait for data.");
                Thread spool=new Thread(()->spool(client,response),"camera-video-buffer");spool.setDaemon(true);spool.start();
            }
        }catch(IOException|RuntimeException e){if(connection!=null)connection.disconnect();client.close();active=null;throw e;}
    }

    private void spool(CameraHttp client,HttpURLConnection response){
        try(InputStream in=response.getInputStream();RandomAccessFile writer=new RandomAccessFile(temporary,"rw")){
            byte[] bytes=new byte[65536];int n;long total=0;
            while((n=in.read(bytes))!=-1){ensureOpen();writer.write(bytes,0,n);total+=n;synchronized(monitor){available=total;monitor.notifyAll();}}
            if(length>=0&&length!=total)throw new IOException("Video buffer is incomplete");
            synchronized(monitor){length=total;complete=true;monitor.notifyAll();}
        }catch(IOException e){if(!closed)failure=e;synchronized(monitor){monitor.notifyAll();}}
        finally{response.disconnect();client.close();active=null;if(closed)temporary.delete();}
    }
    long size(){return length;}
    long buffered(){return ranges?-1:available;}
    boolean supportsRanges(){return ranges;}

    int readAt(long position,byte[] buffer,int offset,int count)throws IOException {
        if(position<0||offset<0||count<0||offset>buffer.length-count)throw new IOException("Invalid video read");
        ensureOpen();if(count==0)return 0;if(length>=0&&position>=length)return -1;
        if(ranges)return rangeRead(position,buffer,offset,count);
        long deadline=System.nanoTime()+120_000_000_000L;
        synchronized(monitor){
            while(position>=available&&!complete&&failure==null&&!closed){
                if(System.nanoTime()>deadline)throw new IOException("Video is still buffering. Save the original to play it locally.");
                try{monitor.wait(500);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Video cancelled",e);}
            }
            ensureOpen();if(failure!=null)throw failure;if(position>=available)return -1;
            reader.seek(position);return reader.read(buffer,offset,(int)Math.min(count,available-position));
        }
    }
    private synchronized int rangeRead(long position,byte[] buffer,int offset,int count)throws IOException {
        if(block==null||position<blockStart||position>=blockStart+block.length){
            CameraHttp client=new CameraHttp(opener,user,password);active=client;
            try{
                ensureOpen();long end=position+Math.min(BLOCK,length-position)-1;
                HttpURLConnection c=client.request(url,Collections.singletonMap("Range","bytes="+position+"-"+end),true);
                try{
                    if(c.getResponseCode()!=206)throw new IOException("Camera stopped honoring video byte ranges");
                    long[] interval=parseRange(c.getHeaderField("Content-Range"),position,BLOCK);
                    if(interval[2]!=length)throw new IOException("The video changed during playback");
                    byte[] bytes=CameraHttp.readBounded(c.getInputStream(),BLOCK);
                    if(bytes.length!=interval[1]-interval[0]+1)throw new IOException("Truncated video byte range");
                    block=bytes;blockStart=position;
                }finally{c.disconnect();}
            }finally{client.close();active=null;}
        }
        int available=(int)Math.min(count,block.length-(position-blockStart));
        System.arraycopy(block,(int)(position-blockStart),buffer,offset,available);return available;
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
        closed=true;CameraHttp client=active;if(client!=null)client.close();
        synchronized(monitor){monitor.notifyAll();if(reader!=null)try{reader.close();}catch(IOException ignored){}}
        temporary.delete();
    }
}
