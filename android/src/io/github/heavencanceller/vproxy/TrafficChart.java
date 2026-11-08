package io.github.heavencanceller.vproxy;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.ArrayDeque;

final class TrafficChart extends View {
    private final ArrayDeque<Float> values=new ArrayDeque<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    TrafficChart(Context context) { super(context); setMinimumHeight((int)(110*getResources().getDisplayMetrics().density)); }
    void sample(float value) { values.add(Math.max(0,value)); if(values.size()>60) values.removeFirst(); invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float w=getWidth(), h=getHeight()-12; float max=1;
        for(float value:values) max=Math.max(max,value);
        paint.setStrokeWidth(1); paint.setColor(Color.rgb(212,223,236));
        for(int i=1;i<4;i++) canvas.drawLine(0,h*i/4,w,h*i/4,paint);
        Path path=new Path(); int i=0;
        for(float value:values) { float x=i*w/59,y=h-value/max*(h-8); if(i++==0) path.moveTo(x,y); else path.lineTo(x,y); }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4); paint.setColor(Color.rgb(30,115,190)); canvas.drawPath(path,paint); paint.setStyle(Paint.Style.FILL);
    }
}
