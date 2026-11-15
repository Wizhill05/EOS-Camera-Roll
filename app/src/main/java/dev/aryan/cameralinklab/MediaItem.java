package dev.aryan.cameralinklab;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class MediaItem {
    final URL url;
    final String name, folder, extension;
    final boolean video;
    private String keyCamera,key;
    synchronized String keyFor(String camera){if(!camera.equals(keyCamera)){key=MediaKey.of(camera,url.getPath());keyCamera=camera;}return key;}

    MediaItem(URL url) {
        this.url = url;
        String path = url.getPath();
        int slash = path.lastIndexOf('/');
        name = decode(path.substring(slash + 1));
        folder = decode(path.substring(path.indexOf("/contents/") + 10, slash));
        extension = name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        video = extension.equals("MP4") || extension.equals("MOV") || extension.equals("CRM");
    }

    static boolean isMedia(String path) {
        return path.matches("(?i).*\\.(jpg|jpeg|cr2|cr3|hif|heif|heic|png|tif|tiff|mp4|mov|crm)$");
    }
    static boolean hasExtension(String path) { return path.substring(path.lastIndexOf('/') + 1).contains("."); }
    private static String decode(String text) {
        try { return URLDecoder.decode(text.replace("+", "%2B"), "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    static int cameraRating(org.json.JSONObject info){Object value=info.opt("rating");if(value instanceof org.json.JSONObject)value=((org.json.JSONObject)value).opt("value");if(value==null||value==org.json.JSONObject.NULL)return -1;String text=value.toString();if(text.equalsIgnoreCase("off"))return 0;try{int n=Integer.parseInt(text);return n>=0&&n<=5?n:-1;}catch(NumberFormatException invalid){return -1;}}
    boolean raw() { return extension.equals("CR2") || extension.equals("CR3"); }
    String mime() {
        if(video) return extension.equals("MOV") ? "video/quicktime" : extension.equals("MP4") ? "video/mp4" : "application/octet-stream";
        if(extension.equals("JPG")||extension.equals("JPEG"))return "image/jpeg";
        if(extension.equals("CR3"))return "image/x-canon-cr3";
        if(extension.equals("CR2"))return "image/x-canon-cr2";
        if(extension.equals("TIF")||extension.equals("TIFF"))return "image/tiff";
        if(extension.equals("PNG"))return "image/png";
        if(extension.startsWith("H"))return "image/heif";
        return "application/octet-stream";
    }
    @Override public boolean equals(Object other) { return other instanceof MediaItem && url.toExternalForm().equals(((MediaItem)other).url.toExternalForm()); }
    @Override public int hashCode() { return url.toExternalForm().hashCode(); }
}
