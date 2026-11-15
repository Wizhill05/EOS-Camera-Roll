package dev.aryan.cameralinklab;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.ImageView;

final class ZoomPhotoView extends ImageView {
    private final Matrix transform=new Matrix();
    private float scale=1,fit=1,lastX,lastY;
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;
    ZoomPhotoView(Context context){
        super(context);setScaleType(ScaleType.MATRIX);setContentDescription("Photo preview. Pinch to zoom, drag to pan, double tap for pixel view or fit.");
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){float next=Math.max(fit,Math.min(Math.max(fit*8,4),scale*detector.getScaleFactor()));transform.postScale(next/scale,next/scale,detector.getFocusX(),detector.getFocusY());scale=next;constrain();setImageMatrix(transform);return true;}
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(android.view.MotionEvent event){return true;}
            @Override public boolean onDoubleTap(android.view.MotionEvent event){if(scale>fit*1.1f)reset();else{float next=Math.max(1,fit*2);transform.postScale(next/scale,next/scale,event.getX(),event.getY());scale=next;constrain();setImageMatrix(transform);}return true;}
        });
    }
    @Override public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);post(this::reset);}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);reset();}
    private void reset(){if(getDrawable()==null||getWidth()==0)return;int w=getDrawable().getIntrinsicWidth(),h=getDrawable().getIntrinsicHeight();if(w<=0||h<=0)return;fit=Math.min(getWidth()/(float)w,getHeight()/(float)h);scale=fit;transform.setScale(fit,fit);transform.postTranslate((getWidth()-w*fit)/2,(getHeight()-h*fit)/2);setImageMatrix(transform);}
    private void constrain(){if(getDrawable()==null)return;RectF bounds=new RectF(0,0,getDrawable().getIntrinsicWidth(),getDrawable().getIntrinsicHeight());transform.mapRect(bounds);float dx=bounds.width()<getWidth()?(getWidth()-bounds.width())/2-bounds.left:bounds.left>0?-bounds.left:bounds.right<getWidth()?getWidth()-bounds.right:0;float dy=bounds.height()<getHeight()?(getHeight()-bounds.height())/2-bounds.top:bounds.top>0?-bounds.top:bounds.bottom<getHeight()?getHeight()-bounds.bottom:0;transform.postTranslate(dx,dy);}
    @Override public boolean onTouchEvent(MotionEvent e){pinch.onTouchEvent(e);gestures.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!pinch.isInProgress()){transform.postTranslate(e.getX()-lastX,e.getY()-lastY);constrain();setImageMatrix(transform);}lastX=e.getX();lastY=e.getY();if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
