package dev.aryan.cameralinklab;

import android.graphics.*;
import android.graphics.drawable.Drawable;

final class LabIcon extends Drawable {
    private final String name;private final Paint paint=new Paint(3);private final int color;
    LabIcon(String name,int color){this.name=name;this.color=color;}
    @Override public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);paint.setColor(color);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);
        Path p=new Path();switch(name){
            case "filter":p.moveTo(4,6);p.lineTo(20,6);p.moveTo(4,12);p.lineTo(20,12);p.moveTo(4,18);p.lineTo(20,18);c.drawCircle(9,6,2.5f,paint);c.drawCircle(15,12,2.5f,paint);c.drawCircle(9,18,2.5f,paint);break;
            case "gallery":c.drawRoundRect(3,3,21,21,4,4,paint);c.drawCircle(8,8,1.5f,paint);p.moveTo(4,18);p.lineTo(10,12);p.lineTo(14,16);p.lineTo(18,11);p.lineTo(21,14);break;
            case "star":for(int i=0;i<10;i++){double angle=-Math.PI/2+i*Math.PI/5;float radius=i%2==0?9:4.2f;float x=12+(float)Math.cos(angle)*radius,y=12+(float)Math.sin(angle)*radius;if(i==0)p.moveTo(x,y);else p.lineTo(x,y);}p.close();break;
            case "saved":c.drawRoundRect(3,3,21,21,4,4,paint);p.moveTo(7,12);p.lineTo(10.5f,15.5f);p.lineTo(17,8.5f);break;
            case "back":case "previous":p.moveTo(15,5);p.lineTo(8,12);p.lineTo(15,19);break;
            case "next":p.moveTo(9,5);p.lineTo(16,12);p.lineTo(9,19);break;
            case "save":p.moveTo(12,3);p.lineTo(12,15);p.moveTo(7,10);p.lineTo(12,15);p.lineTo(17,10);p.moveTo(4,16);p.lineTo(4,21);p.lineTo(20,21);p.lineTo(20,16);break;
            case "refresh":c.drawArc(4,4,20,20,40,285,false,paint);p.moveTo(20,4);p.lineTo(20,10);p.lineTo(14,10);break;
            case "camera":c.drawRoundRect(3,7,21,20,3,3,paint);c.drawCircle(12,13.5f,4,paint);p.moveTo(7,7);p.lineTo(9,3);p.lineTo(15,3);p.lineTo(17,7);break;
            case "folder":p.moveTo(3,6);p.lineTo(9,6);p.lineTo(11,9);p.lineTo(21,9);p.lineTo(21,20);p.lineTo(3,20);p.close();break;
            case "info":c.drawCircle(12,12,9,paint);c.drawPoint(12,7,paint);p.moveTo(12,11);p.lineTo(12,17);break;
            case "close":p.moveTo(6,6);p.lineTo(18,18);p.moveTo(18,6);p.lineTo(6,18);break;
            case "more":paint.setStyle(Paint.Style.FILL);c.drawCircle(12,5,1.6f,paint);c.drawCircle(12,12,1.6f,paint);c.drawCircle(12,19,1.6f,paint);break;
        }c.drawPath(p,paint);c.restore();
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}@Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
