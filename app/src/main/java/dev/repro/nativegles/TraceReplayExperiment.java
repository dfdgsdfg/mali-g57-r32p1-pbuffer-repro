package dev.repro.nativegles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.graphics.BitmapFactory;
import android.opengl.*;
import android.os.SystemClock;
import org.json.JSONObject;
import java.nio.*;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static android.opengl.GLES30.*;

/** Reduction of the captured Impeller sequence, with two contexts in both modes. */
final class TraceReplayExperiment implements GlesExperiment {
    private static final int SIZE=1200, PATCH=24, LIMIT=8, CACHE_SIZE=24, REPEATS=4;
    private final boolean serialized;
    private final Context assetContext;
    private SurfaceTexture screenTexture;
    private Surface screenSurface;
    private boolean grid,window;
    private int gridWidth,gridHeight;
    private final AtomicLong gridFrames=new AtomicLong(), gridDraws=new AtomicLong(), gridOverlaps=new AtomicLong(), lastGrid=new AtomicLong();
    private volatile int gridSourcesSeen,surfaceResizes;
    private final CachedSource[] gridSources=new CachedSource[CACHE_SIZE]; // Raster-owned, after fence consumption.
    private long nextGrid;
    private boolean targetDetached,rasterStopped;
    private MemorySampler memory;
    private boolean completion,boundedFrames,flushReduction,explicitGridFlush;
    private String fenceIsolationPolicy="";
    private boolean gpuAccounting;
    private final ArrayDeque<FrameFence> retainedFrameFences=new ArrayDeque<>(); // Raster owns all retained handles.
    private final AtomicLong framesDeleted=new AtomicLong(),retainedHandles=new AtomicLong();
    static TraceReplayExperiment fenceIsolation(Context context,SurfaceTexture texture,int width,int height,int scenario){
        TraceReplayExperiment experiment=new TraceReplayExperiment(context,texture,width,height,false);
        experiment.fenceIsolationPolicy=(scenario==18||scenario==23)?"immediate_delete":scenario==19?"no_poll_retain":scenario==20?"poll_retain":scenario==21?"poll_delete":"flush_only";
        experiment.completion=scenario==20||scenario==21;experiment.explicitGridFlush=scenario==22||scenario==24;experiment.gpuAccounting=scenario>=23;
        experiment.memory=new MemorySampler(experiment.started,true,experiment.gpuAccounting);return experiment;
    }
    private boolean fenceIsolation(){return !fenceIsolationPolicy.isEmpty();}
    private final ArrayDeque<FrameFence> frameFences=new ArrayDeque<>(); // Raster owns fence creation/waits/deletion.
    private final AtomicLong framesCreated=new AtomicLong(),framesCompleted=new AtomicLong(),pendingFrames=new AtomicLong(),peakPendingFrames=new AtomicLong();
    private final AtomicLong frameWaitCalls=new AtomicLong(),frameWaitTimeouts=new AtomicLong();
    private volatile long oldestFrameAt;
    private static final class FrameFence {
        final long sync,submittedAt;
        FrameFence(long sync,long submittedAt){this.sync=sync;this.submittedAt=submittedAt;}
    }
    static TraceReplayExperiment gridFlush(Context context,SurfaceTexture texture,int width,int height,boolean explicitFlush){
        TraceReplayExperiment experiment=new TraceReplayExperiment(context,texture,width,height,false);
        experiment.flushReduction=true;experiment.explicitGridFlush=explicitFlush;
        experiment.memory=new MemorySampler(experiment.started);return experiment;
    }
    TraceReplayExperiment(Context context,SurfaceTexture texture,int width,int height,boolean window,boolean bounded){
        this(context,texture,width,height,window);completion=true;boundedFrames=bounded;memory=new MemorySampler(started);
    }
    TraceReplayExperiment(Context context,SurfaceTexture texture,int width,int height,boolean window){
        this(context,false);grid=true;this.window=window;screenTexture=texture;
        screenSurface=new Surface(texture);gridWidth=width;gridHeight=height;
    }
    void surfaceChanged(){surfaceResizes++;fail(new IllegalStateException("surface resized during fixed-size experiment"));}
    synchronized void surfaceDestroyed(){
        targetDetached=true;if(running)fail(new IllegalStateException("surface destroyed during experiment"));
        releaseScreenTexture();
    }
    private synchronized void screenRasterStopped(){
        rasterStopped=true;if(screenSurface!=null)screenSurface.release();releaseScreenTexture();
    }
    private void releaseScreenTexture(){
        if(targetDetached&&rasterStopped&&screenTexture!=null){screenTexture.release();screenTexture=null;}
    }

    private final CachedSource[] cache=new CachedSource[CACHE_SIZE];
    private final ArrayBlockingQueue<Job> queue=new ArrayBlockingQueue<>(LIMIT);
    private final ArrayDeque<Job> live=new ArrayDeque<>(); // IO thread owns retirement.
    private final CountDownLatch published=new CountDownLatch(1), rasterReady=new CountDownLatch(1), rasterDone=new CountDownLatch(1);
    private final AtomicLong reads=new AtomicLong(), draws=new AtomicLong(), created=new AtomicLong(), deleted=new AtomicLong(), retired=new AtomicLong();
    private final AtomicLong outputsCreated=new AtomicLong(), outputsDeleted=new AtomicLong(), overlaps=new AtomicLong();
    private final AtomicLong cacheReuses=new AtomicLong(), cropBaselines=new AtomicLong(), cropRepeatChecks=new AtomicLong();
    private final AtomicLong lastRead=new AtomicLong(), lastDraw=new AtomicLong();
    private final long started=SystemClock.elapsedRealtime();
    private volatile boolean running=true, ready;
    private volatile String error="", renderer="", version="", ioContext="", rasterContext="";
    private volatile String ioCall="idle", rasterCall="idle";
    private volatile long ioCallAt, rasterCallAt;
    private volatile int ioTexture, rasterTexture, ioTid, rasterTid;
    private volatile Job reading;
    private EGLDisplay display;
    private EGLConfig config;
    private EGLContext uploadContext;
    TraceReplayExperiment(boolean serialized){this.serialized=serialized;assetContext=null;}
    TraceReplayExperiment(Context context,boolean serialized){
        this.serialized=serialized;assetContext=context.getApplicationContext();
    }
    private boolean cached(){return assetContext!=null;}
    public void start(){if(memory!=null)memory.start();new Thread(this::rasterWorker,"replay-raster").start();new Thread(this::ioWorker,"replay-io").start();}
    public void stop(){running=false;if(memory!=null)memory.stop();}
    private void fail(Throwable e){error=e.toString();stop();}
    private void io(String call){ioCallAt=SystemClock.elapsedRealtime();ioCall=call;}
    private void raster(String call){rasterCallAt=SystemClock.elapsedRealtime();rasterCall=call;}
    private static void check(String where){int e=glGetError();if(e!=GL_NO_ERROR)throw new IllegalStateException(where+" GL 0x"+Integer.toHexString(e));}
    private static void egl(boolean ok,String where){if(!ok)throw new IllegalStateException(where+" EGL 0x"+Integer.toHexString(EGL14.eglGetError()));}
    private EGLContext context(EGLContext share){
        EGLContext c=EGL14.eglCreateContext(display,config,share,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        egl(c!=EGL14.EGL_NO_CONTEXT,"create context");return c;
    }
    private EGLSurface rasterSurface(){
        if(!grid)return surface();
        EGLSurface s=window?EGL14.eglCreateWindowSurface(display,config,screenSurface,new int[]{EGL14.EGL_NONE},0):
            EGL14.eglCreatePbufferSurface(display,config,new int[]{EGL14.EGL_WIDTH,gridWidth,EGL14.EGL_HEIGHT,gridHeight,EGL14.EGL_NONE},0);
        egl(s!=EGL14.EGL_NO_SURFACE,"grid surface");return s;
    }
    private EGLSurface surface(){
        EGLSurface s=EGL14.eglCreatePbufferSurface(display,config,new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
        egl(s!=EGL14.EGL_NO_SURFACE,"pbuffer");return s;
    }
    private void destroy(EGLContext c,EGLSurface s){
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display,s);EGL14.eglDestroyContext(display,c);EGL14.eglReleaseThread();
    }
    private void setup(){
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] v=new int[2];egl(EGL14.eglInitialize(display,v,0,v,1),"initialize");
        EGLConfig[] configs=new EGLConfig[1];int[] n=new int[1];
        egl(EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT|(grid?EGL14.EGL_WINDOW_BIT:0),EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,n,0)&&n[0]>0,"config");
        config=configs[0];uploadContext=context(EGL14.EGL_NO_CONTEXT);
    }
    private static int genTexture(){int[] x=new int[1];glGenTextures(1,x,0);return x[0];}
    private static int genFbo(){int[] x=new int[1];glGenFramebuffers(1,x,0);return x[0];}
    private static void attach(int fbo,int texture){
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("incomplete FBO");
    }
    private static void parameters(){
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    }
    private static final class Job {
        final int texture,index;final long fence;
        final CachedSource source;
        final CountDownLatch enteringRead=new CountDownLatch(1), readDone=new CountDownLatch(1), sampled=new CountDownLatch(1);
        Job(int texture,int index,long fence){this(texture,index,fence,null);}
        Job(int texture,int index,long fence,CachedSource source){this.texture=texture;this.index=index;this.fence=fence;this.source=source;}
    }
    private static final class CachedSource {
        final int texture,index;final long fence;
        final int[] expected; // Eighteen CPU-decoded source pixels, not driver output.
        byte[] cropReference; // Raster-owned repeated-output check; not an independent oracle.
        Job lastUse; // IO-owned: serialize all uses of one cached texture, not just one job.
        CachedSource(int texture,int index,long fence,int[] expected){this.texture=texture;this.index=index;this.fence=fence;this.expected=expected;}
    }
    private boolean await(CountDownLatch latch)throws InterruptedException {
        while(running){if(latch.await(100,TimeUnit.MILLISECONDS))return true;}return false;
    }
    private void release(Job job){
        if(cached())return; // Cache textures/fences live until both workers stop.
        glDeleteSync(job.fence);glDeleteTextures(1,new int[]{job.texture},0);check("source retirement");deleted.incrementAndGet();
    }
    private static int[] sourcePositions(){
        int[] positions=new int[18];int n=0;
        for(int y=150;y<SIZE;y+=300)for(int x=150;x<SIZE;x+=300)positions[n++]=y*SIZE+x;
        positions[n++]=0;positions[n]=SIZE*SIZE-1;return positions;
    }
    private CachedSource cachedSource(int index,ByteBuffer pixels)throws Exception {
        if(cache[index]!=null){cacheReuses.incrementAndGet();return cache[index];}
        io("decode JPEG");Bitmap bitmap;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inScaled=false;
        try(InputStream input=assetContext.getAssets().open(String.format(java.util.Locale.ROOT,"images/img_%02d.jpg",index))){bitmap=BitmapFactory.decodeStream(input,null,options);}
        if(bitmap==null)throw new IllegalStateException("JPEG decode failed "+index);
        int[] rgba=new int[SIZE*SIZE];
        try{
            if(bitmap.getWidth()!=SIZE||bitmap.getHeight()!=SIZE)throw new IllegalStateException("unexpected JPEG dimensions");
            bitmap.getPixels(rgba,0,SIZE,0,0,SIZE,SIZE);
        }finally{bitmap.recycle();}
        int[] positions=sourcePositions(),expected=new int[positions.length];
        for(int i=0;i<positions.length;i++)expected[i]=rgba[positions[i]];
        pixels.clear();for(int color:rgba)pixels.put((byte)(color>>16)).put((byte)(color>>8)).put((byte)color).put((byte)(color>>>24));pixels.flip();
        io("glGenTextures");int texture=genTexture();ioTexture=texture;created.incrementAndGet();
        io("glBindTexture upload");glBindTexture(GL_TEXTURE_2D,texture);parameters();
        io("glTexImage2D 1200x1200 null");glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,SIZE,SIZE,0,GL_RGBA,GL_UNSIGNED_BYTE,null);
        io("glTexSubImage2D JPEG");glTexSubImage2D(GL_TEXTURE_2D,0,0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        io("glGenerateMipmap source");glGenerateMipmap(GL_TEXTURE_2D);
        io("glFenceSync");long fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        if(fence==0)throw new IllegalStateException("source fence creation failed");
        io("glFlush");glFlush();check("cached upload and flush");
        CachedSource source=new CachedSource(texture,index,fence,expected);cache[index]=source;return source;
    }
    private static void verifyCachedSource(ByteBuffer data,CachedSource source){
        int[] positions=sourcePositions();
        for(int i=0;i<positions.length;i++){
            int color=source.expected[i],offset=positions[i]*4;
            int[] expected={(color>>16)&255,(color>>8)&255,color&255,(color>>>24)&255};
            for(int c=0;c<4;c++)if((data.get(offset+c)&255)!=expected[c])throw new IllegalStateException("JPEG pixel mismatch source="+source.index+" sample="+i+" channel="+c);
        }
    }
    private void verifyCachedCrop(ByteBuffer data,CachedSource source){
        if(source.cropReference==null){
            source.cropReference=new byte[PATCH*PATCH*4];
            for(int i=0;i<source.cropReference.length;i++)source.cropReference[i]=data.get(i);
            cropBaselines.incrementAndGet();
        }else{
            for(int i=0;i<source.cropReference.length;i++)if(source.cropReference[i]!=data.get(i))throw new IllegalStateException("JPEG repeated crop mismatch source="+source.index+" byte="+i);
            cropRepeatChecks.incrementAndGet();
        }
    }
    private void uploadPixels(ByteBuffer pixels,int index){
        // Sixteen wide uniform blocks have an independent source/crop oracle.
        pixels.clear();byte[] row=new byte[SIZE*4];
        for(int y=0;y<SIZE;y++){
            for(int x=0;x<SIZE;x++){int p=x*4;row[p]=(byte)(index*7);row[p+1]=(byte)(40+(x/300)*45);row[p+2]=(byte)(40+(y/300)*45);row[p+3]=(byte)255;}
            pixels.put(row);
        }
        pixels.flip();
    }
    private static void verifyPixel(ByteBuffer data,int offset,int index,int x,int y,int tolerance){
        int[] expected={index*7,40+(x/300)*45,40+(y/300)*45,255};
        for(int c=0;c<4;c++)if(Math.abs((data.get(offset+c)&255)-expected[c])>tolerance)
            throw new IllegalStateException("pixel mismatch source="+index+" at "+x+","+y+" channel="+c+" actual="+(data.get(offset+c)&255)+" expected="+expected[c]);
    }
    private static void verifySource(ByteBuffer data,int index){
        for(int y=150;y<SIZE;y+=300)for(int x=150;x<SIZE;x+=300)verifyPixel(data,(y*SIZE+x)*4,index,x,y,0);
        verifyPixel(data,0,index,0,0,0);verifyPixel(data,(SIZE*SIZE-1)*4,index,SIZE-1,SIZE-1,0);
    }
    private static void verifyCrop(ByteBuffer data,int index){
        // Away from block boundaries, mip/interpolation rounding can differ by at most one.
        for(int y=3;y<PATCH;y+=6)for(int x=3;x<PATCH;x+=6)
            verifyPixel(data,(y*PATCH+x)*4,index,(2*x+1)*SIZE/(PATCH*4),(2*y+1)*SIZE/(PATCH*4),1);
    }
    private void ioWorker(){
        EGLSurface s=null;boolean peerStopped=false;
        try{
            ioTid=android.os.Process.myTid();setup();s=surface();egl(EGL14.eglMakeCurrent(display,s,s,uploadContext),"IO current");
            ioContext=uploadContext.toString();renderer=glGetString(GL_RENDERER);version=glGetString(GL_VERSION);
            ByteBuffer pixels=ByteBuffer.allocateDirect(SIZE*SIZE*4), buffer=ByteBuffer.allocateDirect(SIZE*SIZE*4);
            published.countDown();if(!rasterReady.await(20,TimeUnit.SECONDS))throw new IllegalStateException("raster initialization timeout");
            if(!running)return;long now=SystemClock.elapsedRealtime();lastRead.set(now);lastDraw.set(now);lastGrid.set(now);ready=true;int index=0;long iteration=0;
            while(running){
                if(live.size()==LIMIT){Job old=live.peekFirst();io("await raster retirement");if(!await(old.sampled))break;live.removeFirst();io("retire source");release(old);retired.incrementAndGet();}
                Job job;
                if(cached()){
                    index=(int)((iteration/REPEATS)%CACHE_SIZE);CachedSource source=cachedSource(index,pixels);
                    if(serialized&&source.lastUse!=null){io("await previous cached-source sample");if(!await(source.lastUse.sampled))break;}
                    ioTexture=source.texture;job=new Job(source.texture,index,source.fence,source);
                    source.lastUse=job;
                }else{
                    uploadPixels(pixels,index);io("glGenTextures");int texture=genTexture();ioTexture=texture;created.incrementAndGet();
                    io("glBindTexture upload");glBindTexture(GL_TEXTURE_2D,texture);parameters();
                    io("glTexImage2D 1200x1200 null");glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,SIZE,SIZE,0,GL_RGBA,GL_UNSIGNED_BYTE,null);
                    io("glTexSubImage2D 1200x1200");glTexSubImage2D(GL_TEXTURE_2D,0,0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                    io("glGenerateMipmap source");glGenerateMipmap(GL_TEXTURE_2D);
                    io("glFenceSync");long fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(fence==0)throw new IllegalStateException("source fence creation failed");
                    io("glFlush");glFlush();check("upload and flush");
                    job=new Job(texture,index,fence);
                }
                live.addLast(job);
                io("create/attach source FBO");int fbo=genFbo();attach(fbo,job.texture);buffer.clear();queue.add(job);
                io("glReadPixels 1200x1200");reading=job;job.enteringRead.countDown();
                glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,buffer);
                reading=null;io("verify source");check("source readback");if(cached())verifyCachedSource(buffer,job.source);else verifySource(buffer,index);
                reads.incrementAndGet();lastRead.set(SystemClock.elapsedRealtime());job.readDone.countDown();
                io("delete source FBO");glDeleteFramebuffers(1,new int[]{fbo},0);check("source FBO deletion");
                if(!cached())index=(index+1)%32;iteration++;
            }
        }catch(Throwable e){fail(e);}
        finally{
            published.countDown();try{peerStopped=rasterDone.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            // Retain shared resources if a peer is still inside a driver call.
            if(peerStopped&&s!=null){
                io("cleanup");while(!live.isEmpty())release(live.removeFirst());
                if(cached())for(CachedSource source:cache)if(source!=null){glDeleteSync(source.fence);glDeleteTextures(1,new int[]{source.texture},0);}
                destroy(uploadContext,s);EGL14.eglTerminate(display);
            }
            io("stopped");
        }
    }
    private static int shader(int type,String source){
        int x=glCreateShader(type);glShaderSource(x,source);glCompileShader(x);int[] ok=new int[1];glGetShaderiv(x,GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException(glGetShaderInfoLog(x));return x;
    }
    private void rasterWorker(){
        EGLContext c=null;EGLSurface s=null;int program=0,vao=0;
        try{
            rasterTid=android.os.Process.myTid();if(!published.await(30,TimeUnit.SECONDS))throw new IllegalStateException("IO publication timeout");if(!running)return;
            c=context(uploadContext);s=rasterSurface();egl(EGL14.eglMakeCurrent(display,s,s,c),"raster current");rasterContext=c.toString();if(grid)egl(EGL14.eglSwapInterval(display,0),"swap interval");
            int vs=shader(GL_VERTEX_SHADER,"#version 300 es\nout vec2 uv;uniform float uvScale;void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));uv=p*uvScale;gl_Position=vec4(p*2.0-1.0,0,1);}");
            int fs=shader(GL_FRAGMENT_SHADER,"#version 300 es\nprecision highp float;in vec2 uv;uniform sampler2D src;out vec4 color;void main(){color=texture(src,uv);}");
            program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);int[] ok=new int[1];glGetProgramiv(program,GL_LINK_STATUS,ok,0);
            if(ok[0]==0)throw new IllegalStateException(glGetProgramInfoLog(program));glDeleteShader(vs);glDeleteShader(fs);glUseProgram(program);glUniform1i(glGetUniformLocation(program,"src"),0);int uvScale=glGetUniformLocation(program,"uvScale");glUniform1f(uvScale,0.5f);
            int[] x=new int[1];glGenVertexArrays(1,x,0);vao=x[0];glBindVertexArray(vao);glActiveTexture(GL_TEXTURE0);glDisable(GL_DITHER);check("raster setup");
            ByteBuffer buffer=ByteBuffer.allocateDirect(PATCH*PATCH*4);rasterReady.countDown();
            while(running){
                raster("await source");Job job=queue.poll(100,TimeUnit.MILLISECONDS);if(job==null)continue;
                rasterTexture=job.texture;if(!await(job.enteringRead))break;
                if(serialized){raster("await IO read completion");if(!await(job.readDone))break;}
                raster("glWaitSync");glWaitSync(job.fence,0,GL_TIMEOUT_IGNORED);
                raster("glBindTexture source");Job currentRead=reading;if(currentRead!=null&&currentRead.texture==job.texture)overlaps.incrementAndGet();glBindTexture(GL_TEXTURE_2D,job.texture);
                raster("allocate output");int output=genTexture(),fbo=genFbo();glBindTexture(GL_TEXTURE_2D,output);
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,PATCH,PATCH,0,GL_RGBA,GL_UNSIGNED_BYTE,null);parameters();outputsCreated.incrementAndGet();attach(fbo,output);
                glUniform1f(uvScale,0.5f);glViewport(0,0,PATCH,PATCH);raster("glBindTexture draw source");glBindTexture(GL_TEXTURE_2D,job.texture);
                raster("glDrawArrays");glDrawArrays(GL_TRIANGLES,0,3);glBindTexture(GL_TEXTURE_2D,output);
                raster("glGenerateMipmap output");glGenerateMipmap(GL_TEXTURE_2D);buffer.clear();
                raster("glReadPixels 24x24");glReadPixels(0,0,PATCH,PATCH,GL_RGBA,GL_UNSIGNED_BYTE,buffer);
                raster("verify crop");check("sample/read output");if(cached())verifyCachedCrop(buffer,job.source);else verifyCrop(buffer,job.index);
                raster("delete output/FBO");glDeleteFramebuffers(1,new int[]{fbo},0);glDeleteTextures(1,new int[]{output},0);check("output retirement");outputsDeleted.incrementAndGet();
                if(grid){
                    if(gridSources[job.index]==null){gridSources[job.index]=job.source;gridSourcesSeen++;}
                    if(SystemClock.elapsedRealtime()>=nextGrid)drawGrid(s,uvScale);
                }
                draws.incrementAndGet();lastDraw.set(SystemClock.elapsedRealtime());job.sampled.countDown();
            }
        }catch(Throwable e){fail(e);}
        finally{
            rasterReady.countDown();
            if(completion&&!drainFramesForCleanup()){
                // Keep the EGL/native window and shared sources alive if submitted grid work cannot retire.
                raster("retained pending grid frames");return;
            }
            if(fenceIsolation()&&c!=null&&s!=null){
                // Measurement has ended. Complete all grid work before shared-resource retirement.
                // If this driver call hangs, rasterDone remains unsignaled and IO retains sources.
                raster("cleanup glFinish");glFinish();check("isolation cleanup finish");
                while(!retainedFrameFences.isEmpty()){glDeleteSync(retainedFrameFences.removeFirst().sync);framesDeleted.incrementAndGet();retainedHandles.decrementAndGet();}
            }
            if(c!=null&&s!=null){raster("cleanup");if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(1,new int[]{vao},0);destroy(c,s);}
            // TextureView transfers ownership at destruction; release only after EGL stops using it.
            if(grid)screenRasterStopped();
            raster("stopped");rasterDone.countDown();
        }
    }
    private void reapFrames(long timeout){
        while(!frameFences.isEmpty()){
            FrameFence frame=frameFences.peekFirst();raster("grid glClientWaitSync");frameWaitCalls.incrementAndGet();
            int result=glClientWaitSync(frame.sync,0,timeout);
            if(result==GL_TIMEOUT_EXPIRED){frameWaitTimeouts.incrementAndGet();break;}
            if(result!=GL_ALREADY_SIGNALED&&result!=GL_CONDITION_SATISFIED)throw new IllegalStateException("grid fence wait failed: 0x"+Integer.toHexString(result));
            if(fenceIsolationPolicy.equals("poll_retain")){
                retainedFrameFences.addLast(frame);retainedHandles.incrementAndGet();
            }else{raster("grid glDeleteSync completed");glDeleteSync(frame.sync);framesDeleted.incrementAndGet();}
            frameFences.removeFirst();framesCompleted.incrementAndGet();
        }
        pendingFrames.set(frameFences.size());oldestFrameAt=frameFences.isEmpty()?0:frameFences.peekFirst().submittedAt;
        check("grid completion polling");
    }
    private boolean drainFramesForCleanup(){
        long deadline=SystemClock.elapsedRealtime()+2000;
        try{while(!frameFences.isEmpty()&&SystemClock.elapsedRealtime()<deadline)reapFrames(1000000L);}
        catch(Throwable e){fail(e);return false;}
        return frameFences.isEmpty();
    }
    private void drawGrid(EGLSurface s,int uvScale){
        if(completion){
            reapFrames(0);
            while(boundedFrames&&!frameFences.isEmpty()&&running)reapFrames(1000000L);
            if(!running)return;
        }
        raster("grid bind default framebuffer");glBindFramebuffer(GL_FRAMEBUFFER,0);glUniform1f(uvScale,1.0f);
        glViewport(0,0,gridWidth,gridHeight);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
        for(int i=0;i<CACHE_SIZE;i++){
            CachedSource source=gridSources[i];if(source==null)continue;
            rasterTexture=source.texture;raster("grid glWaitSync");glWaitSync(source.fence,0,GL_TIMEOUT_IGNORED);
            raster("grid glBindTexture source");Job currentRead=reading;
            if(currentRead!=null&&currentRead.texture==source.texture)gridOverlaps.incrementAndGet();
            glBindTexture(GL_TEXTURE_2D,source.texture);
            int col=i%4,row=i/4,x=col*gridWidth/4,y=row*gridHeight/6;
            glViewport(x,y,(col+1)*gridWidth/4-x,(row+1)*gridHeight/6-y);
            raster("grid glDrawArrays");glDrawArrays(GL_TRIANGLES,0,3);gridDraws.incrementAndGet();
        }
        check("grid draws");
        if(completion||(fenceIsolation()&&!fenceIsolationPolicy.equals("flush_only"))){
            raster("grid glFenceSync");long sync=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if(sync==0)throw new IllegalStateException("grid fence creation failed");
            long submittedAt=SystemClock.elapsedRealtime();FrameFence frame=new FrameFence(sync,submittedAt);framesCreated.incrementAndGet();
            if(completion){
                frameFences.addLast(frame);pendingFrames.set(frameFences.size());peakPendingFrames.set(Math.max(peakPendingFrames.get(),frameFences.size()));oldestFrameAt=frameFences.peekFirst().submittedAt;
            }else if(fenceIsolationPolicy.equals("no_poll_retain")){retainedFrameFences.addLast(frame);retainedHandles.incrementAndGet();}
            raster("grid glFlush");glFlush();check("grid fence and flush");
            if(fenceIsolationPolicy.equals("immediate_delete")){raster("grid glDeleteSync immediate");glDeleteSync(sync);framesDeleted.incrementAndGet();check("grid immediate sync deletion");}
        }
        if(explicitGridFlush){raster("grid glFlush");glFlush();check("grid explicit flush");}
        raster("eglSwapBuffers grid");egl(EGL14.eglSwapBuffers(display,s),"grid swap");
        gridFrames.incrementAndGet();long now=SystemClock.elapsedRealtime();lastGrid.set(now);nextGrid=now+16;if(completion)reapFrames(0);
    }
    public JSONObject snapshot(){
        long now=SystemClock.elapsedRealtime();JSONObject j=new JSONObject();
        try{
            long oldest=oldestFrameAt,frameAge=oldest==0?0:Math.max(0,now-oldest);
            long io=ready?now-lastRead.get():0,raster=ready?now-lastDraw.get():0;
            String state=!error.isEmpty()?"error":ready?(io>10000||raster>10000||(grid&&now-lastGrid.get()>10000)||(completion&&frameAge>10000)?"stalled":"running"):(now-started>30000?"setup_timeout":"starting");
            j.put("profile","impeller_trace_reduction").put("mode",serialized?"serialized":"concurrent").put("context_count",2);
            j.put("source_size",SIZE).put("source_storage","mutable").put("source_content","4x4_blocks").put("crop","origin_half").put("max_inflight",LIMIT);
            j.put("upload_sync","per_resource_fence").put("source_lifetime","per_handoff").put("source_schedule","bounded_handoff").put("output_lifetime","per_iteration").put("source_fbo_lifetime","per_iteration");
            j.put("sources_created",created.get()).put("sources_deleted",deleted.get()).put("sources_retired",retired.get()).put("outputs_created",outputsCreated.get()).put("outputs_deleted",outputsDeleted.get());
            j.put("same_source_bind_during_io_read",overlaps.get()).put("io_call",ioCall).put("raster_call",rasterCall).put("io_call_ms",now-ioCallAt).put("raster_call_ms",now-rasterCallAt);
            if(cached()){
                j.put("profile","cached_jpeg_reduction").put("source_content","repro_jpeg_android_decode").put("source_lifetime","resident_cache").put("source_schedule","bounded_cached_handoff");
                j.put("sources_retired",0).put("handoffs_retired",retired.get());
                j.put("cache_capacity",CACHE_SIZE).put("consecutive_uses",REPEATS).put("cache_reuses",cacheReuses.get()).put("crop_baselines",cropBaselines.get()).put("crop_repeat_checks",cropRepeatChecks.get());
                j.put("source_oracle","18_cpu_decoded_pixels").put("crop_oracle","repeat_matches_first_output");
            }
            if(grid){
                j.put("profile","cached_jpeg_grid_reduction").put("raster_surface",window?"window":"pbuffer").put("egl_config_surfaces","window_and_pbuffer");
                j.put("grid_layout","4x6").put("grid_interval_ms",16).put("swap_interval",0).put("grid_width",gridWidth).put("grid_height",gridHeight);
                j.put("grid_frames",gridFrames.get()).put("grid_draws",gridDraws.get()).put("grid_sources_seen",gridSourcesSeen).put("grid_stall_ms",ready?now-lastGrid.get():0);
                j.put("grid_bind_during_io_read",gridOverlaps.get()).put("surface_resizes",surfaceResizes);
            }
            if(flushReduction){
                j.put("profile","cached_jpeg_grid_flush_reduction").put("grid_submission",explicitGridFlush?"explicit_flush":"unchanged");
                j.put("grid_wait_policy","none").put("grid_max_inflight_frames",0).put("grid_fence_sync","none");
                j.put("grid_fences_created",framesCreated.get()).put("grid_fences_completed",framesCompleted.get()).put("grid_pending_frames",pendingFrames.get()).put("grid_peak_pending_frames",peakPendingFrames.get());
                j.put("grid_oldest_pending_ms",frameAge).put("grid_wait_calls",frameWaitCalls.get()).put("grid_wait_timeouts",frameWaitTimeouts.get()).put("memory",memory.snapshot());
            }
            if(completion){
                j.put("profile","cached_jpeg_grid_completion_reduction").put("grid_wait_policy",boundedFrames?"bounded":"observe").put("grid_max_inflight_frames",boundedFrames?1:0).put("grid_fence_sync","fence_then_flush");
                j.put("grid_fences_created",framesCreated.get()).put("grid_fences_completed",framesCompleted.get()).put("grid_pending_frames",pendingFrames.get()).put("grid_peak_pending_frames",peakPendingFrames.get());
                j.put("grid_oldest_pending_ms",frameAge).put("grid_wait_calls",frameWaitCalls.get()).put("grid_wait_timeouts",frameWaitTimeouts.get()).put("memory",memory.snapshot());
            }
            if(fenceIsolation()){
                j.put("profile","cached_jpeg_grid_fence_isolation").put("gpu_accounting_enabled",gpuAccounting).put("grid_fence_policy",fenceIsolationPolicy).put("grid_submission","explicit_flush");
                j.put("grid_wait_policy",completion?"observe":"none").put("grid_max_inflight_frames",0).put("grid_fence_sync",fenceIsolationPolicy.equals("flush_only")?"none":"fence_then_flush");
                j.put("grid_fences_created",framesCreated.get()).put("grid_fences_completed",framesCompleted.get()).put("grid_fences_deleted",framesDeleted.get());
                j.put("grid_handles_retained",retainedHandles.get()+(completion?pendingFrames.get():0)).put("grid_completion_tracked",completion);
                j.put("grid_pending_frames",completion?pendingFrames.get():-1).put("grid_peak_pending_frames",completion?peakPendingFrames.get():-1).put("grid_oldest_pending_ms",completion?frameAge:-1);
                j.put("grid_wait_calls",frameWaitCalls.get()).put("grid_wait_timeouts",frameWaitTimeouts.get()).put("memory",memory.snapshot());
            }
            j.put("io_texture",ioTexture).put("raster_texture",rasterTexture).put("io_tid",ioTid).put("raster_tid",rasterTid).put("io_context",ioContext).put("raster_context",rasterContext);
            j.put("state",state).put("elapsed_seconds",(now-started)/1000).put("readbacks",reads.get()).put("snapshots",draws.get()).put("io_stall_ms",io).put("raster_stall_ms",raster).put("gl_renderer",renderer).put("gl_version",version).put("error",error);
        }catch(Exception e){throw new IllegalStateException(e);}return j;
    }
}
