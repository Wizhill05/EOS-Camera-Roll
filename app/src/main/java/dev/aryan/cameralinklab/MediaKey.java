package dev.aryan.cameralinklab;

import java.nio.charset.StandardCharsets;
import java.security.*;

final class MediaKey {
    static String of(String camera,String path){
        String stablePath=path.replaceFirst("^/ccapi/ver[0-9]+/contents/","/contents/");
        try{return CameraHttp.hex(MessageDigest.getInstance("SHA-256").digest((camera+"\n"+stablePath).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static boolean matchesRating(int rating,int minimum){return minimum==0||rating>=minimum;}
}
