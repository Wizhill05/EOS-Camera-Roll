package dev.aryan.cameralinklab;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class GalleryLog {
    static synchronized void add(Context context,String message) {
        File file=new File(context.getFilesDir(),"media-log.txt");
        try {
            if(file.length()>180000){byte[] data=java.nio.file.Files.readAllBytes(file.toPath());try(FileOutputStream out=new FileOutputStream(file)){out.write(data,Math.max(0,data.length-90000),Math.min(data.length,90000));}}
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write((new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+message.replaceAll("[\\r\\n]+"," ")+"\n").getBytes(StandardCharsets.UTF_8));}
        }catch(IOException ignored){}
    }
    static synchronized String read(Context context) {try{return new String(java.nio.file.Files.readAllBytes(new File(context.getFilesDir(),"media-log.txt").toPath()),StandardCharsets.UTF_8);}catch(IOException e){return "No media activity yet.";}}
}
