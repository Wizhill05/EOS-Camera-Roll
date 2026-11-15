package dev.aryan.cameralinklab;

import android.graphics.Bitmap;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;

final class RawRenderer {
    private static final AtomicLong jobs=new AtomicLong();
    private static final boolean available;
    static{boolean loaded;try{System.loadLibrary("cameraraw");loaded=true;}catch(UnsatisfiedLinkError e){loaded=false;}available=loaded;}
    static long job(){return jobs.incrementAndGet();}
    static Bitmap render(File file,long job)throws IOException{
        if(!available)throw new IOException("Full RAW rendering is unavailable on this processor");
        return renderNative(file.getAbsolutePath(),job);
    }
    static String[] capture(File file)throws IOException{if(!available)throw new IOException("RAW metadata unavailable on this processor");return captureNative(file.getAbsolutePath());}
    private static native String[] captureNative(String path)throws IOException;
    static void cancel(long job){if(available)cancelNative(job);}
    private static native Bitmap renderNative(String path,long job)throws IOException;
    private static native void cancelNative(long job);
}
