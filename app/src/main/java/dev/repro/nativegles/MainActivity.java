package dev.repro.nativegles;

import android.app.Activity;
import android.os.*;
import android.util.Log;
import android.view.WindowManager;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private GlesExperiment experiment;
    private long deadline;
    private boolean finished;
    private TextView text;
    private int duration;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int scenario=getIntent().getIntExtra("scenario",1);
        int mode=getIntent().getIntExtra("mode",scenario==2?1:0);
        duration=Math.max(15,Math.min(900,getIntent().getIntExtra("duration_seconds",600)));
        text=new TextView(this);text.setPadding(24,48,24,24);
        if(scenario>=11&&scenario<=24){
            TextureView view=new TextureView(this);FrameLayout layout=new FrameLayout(this);layout.addView(view);
            // A small translucent status line keeps the image grid visible.
            text.setTextSize(10);text.setBackgroundColor(0x66000000);text.setTextColor(0xffffffff);text.setPadding(8,8,8,8);
            layout.addView(text,new FrameLayout.LayoutParams(-1,100));setContentView(layout);
            view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
                public void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){
                    if(experiment!=null)return;
                    experiment=scenario>=18?TraceReplayExperiment.fenceIsolation(MainActivity.this,texture,width,height,scenario):scenario>=16?TraceReplayExperiment.gridFlush(MainActivity.this,texture,width,height,scenario==17):scenario>=13?new TraceReplayExperiment(MainActivity.this,texture,width,height,scenario==15,scenario!=13):new TraceReplayExperiment(MainActivity.this,texture,width,height,scenario==12);
                    deadline=SystemClock.elapsedRealtime()+duration*1000L;experiment.start();handler.post(tick);
                }
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){if(experiment!=null)((TraceReplayExperiment)experiment).surfaceChanged();}
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){if(experiment==null)return true;((TraceReplayExperiment)experiment).surfaceDestroyed();return false;}
                public void onSurfaceTextureUpdated(SurfaceTexture texture){}
            });
            return;
        }
        setContentView(text);
        experiment=scenario==9||scenario==10?new TraceReplayExperiment(this,scenario==10):scenario==7||scenario==8?new TraceReplayExperiment(scenario==8):new NativeExperiment(mode==1?1:0,scenario==3,scenario==4,scenario==5?1:scenario==6?2:0);deadline=SystemClock.elapsedRealtime()+duration*1000L;
        experiment.start();handler.post(tick);
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(finished)return;
        JSONObject state=experiment.snapshot();
        try {
            state.put("device",Build.DEVICE).put("model",Build.MODEL).put("api",Build.VERSION.SDK_INT).put("duration_seconds",duration);
            Log.i("NATIVE_HEARTBEAT",state.toString());text.setText(state.has("raster_surface")?state.optString("raster_surface")+" "+state.optString("state")+" reads="+state.optLong("readbacks")+" crops="+state.optLong("snapshots")+" frames="+state.optLong("grid_frames"):state.toString(2));
            String status=state.getString("state");
            if(status.equals("error")||status.equals("stalled")||status.equals("setup_timeout"))complete(state,"failed");
            else if(SystemClock.elapsedRealtime()>=deadline)complete(state,state.getLong("readbacks")>0&&state.getLong("snapshots")>0&&status.equals("running")?"passed":"incomplete");
            else handler.postDelayed(this,1000);
        }catch(Exception e){Log.e("NATIVE_ERROR","reporting failed",e);experiment.stop();finished=true;finish();}
    }};
    private void complete(JSONObject state,String outcome)throws Exception {
        finished=true;state.put("outcome",outcome);Log.i("NATIVE_RESULT",state.toString());
        try(OutputStream out=openFileOutput("last-result.json",MODE_PRIVATE)){out.write(state.toString(2).getBytes(StandardCharsets.UTF_8));}
        if(getIntent().getData()!=null) {
            try(OutputStream out=getContentResolver().openOutputStream(getIntent().getData(),"w")) {
                if(out!=null)out.write(state.toString().getBytes(StandardCharsets.UTF_8));
            }catch(Exception e){Log.e("NATIVE_REPORT","Game-loop result URI write failed",e);}
        }
        experiment.stop();finish();
    }
    @Override public void onDestroy(){handler.removeCallbacks(tick);if(experiment!=null)experiment.stop();super.onDestroy();}
}
