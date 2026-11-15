package dev.aryan.cameralinklab;

import java.net.*;
import java.io.*;
import javax.net.ssl.SSLException;

final class LibraryIssue {
    final String title,detail;
    LibraryIssue(String title,String detail){this.title=title;this.detail=detail;}
    static boolean connectionLost(Throwable error){
        for(Throwable e=error;e!=null;e=e.getCause()){
            if(e instanceof SocketException||e instanceof SocketTimeoutException||e instanceof UnknownHostException||e instanceof EOFException||e instanceof SSLException)return true;
            if(e instanceof CameraHttp.StatusException){int status=((CameraHttp.StatusException)e).status;if(status==429||status>=500)return true;}
        }
        return false;
    }
    static boolean stopsBackgroundLoading(Throwable error){
        if(connectionLost(error))return true;
        for(Throwable e=error;e!=null;e=e.getCause()){
            if(e instanceof CameraHttp.ResponseTooLargeException)return true;
            if(e instanceof CameraHttp.StatusException&&((CameraHttp.StatusException)e).status==403)return true;
            if(e.getMessage()!=null&&e.getMessage().contains("401"))return true;
        }
        return false;
    }
    static LibraryIssue from(Throwable error){
        if(error instanceof CameraHttp.ResponseTooLargeException)return new LibraryIssue("Camera preview unavailable","The camera returned an oversized response. Background loading has stopped. Refresh to retry.");
        for(Throwable e=error;e!=null;e=e.getCause()){
            if(e instanceof CameraHttp.BusyException)return new LibraryIssue("Camera request is still finishing","Wait for the current request to close, then refresh. Background loading has stopped.");
            if(e instanceof UnknownHostException)return new LibraryIssue("Device not found","Check the camera address, then refresh. The camera may have a new address after reconnecting.");
            if(e instanceof ConnectException||e instanceof NoRouteToHostException||e instanceof SocketTimeoutException||e instanceof SocketException||e instanceof EOFException)return new LibraryIssue("Camera out of reach","Check the camera and Wi-Fi connection, then refresh. If it still does not respond, stop CCAPI on the camera and start it again. The app cannot reset a stuck camera service.");
            if(e instanceof SSLException)return new LibraryIssue("Camera certificate not trusted","Use the working HTTP camera address, or configure trust for the camera's HTTPS certificate.");
            if(e instanceof CameraHttp.StatusException){int status=((CameraHttp.StatusException)e).status;
                if(status==403)return new LibraryIssue("Camera refused access","The camera is reachable. Check CCAPI mode and account settings on the camera, then reconnect.");
                if(status==404)return new LibraryIssue("Camera API not found","The device replied, but that camera endpoint is unavailable. Check the address shown by CCAPI.");
                if(status>=500)return new LibraryIssue("Camera is busy","The camera replied with an error. Let it finish its current operation, then refresh.");
            }
            String message=e.getMessage();if(message!=null&&message.contains("401"))return new LibraryIssue("Camera sign-in required","The camera is reachable. Open connection settings and check the CCAPI username and password.");
        }
        return new LibraryIssue("Could not read the library","Check the connection and refresh. Your cached library has been kept. Connection details are available in the log.");
    }
}
