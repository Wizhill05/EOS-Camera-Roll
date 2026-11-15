package dev.aryan.cameralinklab;

import android.content.*;
import android.media.ExifInterface;
import java.io.*;
import java.util.*;
import org.json.*;

final class CaptureMetadata {
    private final SharedPreferences preferences;
    CaptureMetadata(Context context){preferences=context.getSharedPreferences("capture-metadata",0);}
    JSONObject get(String key){try{return new JSONObject(preferences.getString(key,"{}"));}catch(JSONException invalid){return new JSONObject();}}
    void merge(String key,JSONObject update){JSONObject data=get(key);Iterator<String> keys=update.keys();while(keys.hasNext()){String field=keys.next();Object value=update.opt(field);if(Arrays.asList("rating","shutterspeed","exposuretime","aperture","fnumber","iso","isospeed","focallength","lens","lensmodel","camera","model","captured","datetimeoriginal","whitebalance","exposurebias","flash","metering","width","height","filesize","lastmodifieddate").contains(field)&&value!=null&&value!=JSONObject.NULL&&!(value instanceof JSONObject)&&!(value instanceof JSONArray)&&!value.toString().trim().isEmpty()&&value.toString().length()<=512)try{data.put(field,value);}catch(JSONException ignored){}}preferences.edit().putString(key,data.toString()).putFloat("iso_"+key,(float)number(value(data,"iso","isospeed"))).apply();}
    float iso(String key){return preferences.getFloat("iso_"+key,Float.NaN);}
    static int rating(JSONObject info){Object value=info.opt("rating");if(value instanceof JSONObject)value=((JSONObject)value).opt("value");if(value==null||value==JSONObject.NULL)return -1;String text=value.toString();if(text.equalsIgnoreCase("off"))return 0;try{int n=Integer.parseInt(text);return n>=0&&n<=5?n:-1;}catch(NumberFormatException invalid){return -1;}}
    void camera(String key,JSONObject info,Context context){merge(key,info);int stars=rating(info);if(stars>=0)new MediaStateStore(context).cameraRating(key,stars);}
    void original(String key,File file,Context context){
        try{ExifInterface exif=new ExifInterface(file.getPath());JSONObject data=new JSONObject();String[][] fields={{"shutterspeed",ExifInterface.TAG_EXPOSURE_TIME},{"aperture",ExifInterface.TAG_F_NUMBER},{"iso",ExifInterface.TAG_ISO_SPEED_RATINGS},{"focallength",ExifInterface.TAG_FOCAL_LENGTH},{"lens","LensModel"},{"camera",ExifInterface.TAG_MODEL},{"captured",ExifInterface.TAG_DATETIME_ORIGINAL},{"whitebalance",ExifInterface.TAG_WHITE_BALANCE},{"exposurebias",ExifInterface.TAG_EXPOSURE_BIAS_VALUE},{"flash",ExifInterface.TAG_FLASH},{"metering",ExifInterface.TAG_METERING_MODE},{"width",ExifInterface.TAG_IMAGE_WIDTH},{"height",ExifInterface.TAG_IMAGE_LENGTH}};
            for(String[] field:fields){String value=exif.getAttribute(field[1]);if(value!=null)data.put(field[0],value);}merge(key,data);
        }catch(IOException|JSONException ignored){}
    }
    void raw(String key,File file){try{String[] values=RawRenderer.capture(file);String[] fields={"shutterspeed","aperture","iso","focallength","lens","camera","width","height"};JSONObject data=new JSONObject();for(int i=0;i<Math.min(fields.length,values.length);i++)if(values[i]!=null&&!values[i].isEmpty())data.put(fields[i],values[i]);merge(key,data);}catch(IOException|JSONException ignored){}}
    static String value(JSONObject data,String...fields){for(String field:fields){String value=data.optString(field,"");if(!value.isEmpty())return value;}return "";}
    static double number(String text){try{String[] parts=text.split("/");return parts.length==2?Double.parseDouble(parts[0])/Double.parseDouble(parts[1]):Double.parseDouble(text);}catch(Exception invalid){return Double.NaN;}}
    String summary(String key){JSONObject data=get(key);List<String> values=new ArrayList<>();String shutter=value(data,"shutterspeed","exposuretime"),aperture=value(data,"aperture","fnumber"),iso=value(data,"iso","isospeed");
        if(!shutter.isEmpty()){double n=number(shutter);values.add(Double.isFinite(n)&&n>0?(n<1?"1/"+Math.round(1/n)+" s":String.format(Locale.US,"%.2g s",n)):shutter);}
        if(!aperture.isEmpty())values.add(aperture.toLowerCase(Locale.ROOT).startsWith("f")?aperture:"f/"+aperture);
        if(!iso.isEmpty())values.add("ISO "+iso);return String.join("   ",values);
    }
    String details(String key){JSONObject data=get(key);StringBuilder out=new StringBuilder();String[][] fields={{"Shutter","shutterspeed","exposuretime"},{"Aperture","aperture","fnumber"},{"ISO","iso","isospeed"},{"Focal length","focallength"},{"Lens","lens","lensmodel"},{"Camera","camera","model"},{"Captured","captured","datetimeoriginal"},{"Exposure compensation","exposurebias"},{"White balance","whitebalance"},{"Flash","flash"},{"Metering mode","metering"},{"Width","width"},{"Height","height"}};
        for(String[] field:fields){String v=value(data,Arrays.copyOfRange(field,1,field.length));if(!v.isEmpty())out.append(field[0]).append(": ").append(v).append('\n');}return out.length()==0?"Capture settings are not available yet. Open the original photo to read its metadata.":out.toString().trim();}
}
