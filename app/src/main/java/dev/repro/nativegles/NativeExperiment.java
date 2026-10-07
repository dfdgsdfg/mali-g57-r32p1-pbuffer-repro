package dev.repro.nativegles;

import android.opengl.*;
import android.os.SystemClock;
import org.json.JSONObject;
import java.nio.*;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static android.opengl.GLES30.*;

/** Explicitly ordered immutable uploads and context-local outputs. */
public final class NativeExperiment implements GlesExperiment {
    private static final int SIZE=256, COUNT=32, PATCH=24;
    private final int mode;
    private final boolean fenceSync, outputChurn;
    // 0: legacy loops, 1: matched resident-source handoff, 2: new source per handoff.
    private final int sourcePipeline;
    private final ArrayBlockingQueue<SourceJob> sourceQueue=new ArrayBlockingQueue<>(COUNT);
    private final ArrayDeque<SourceJob> sourceJobs=new ArrayDeque<>();
    private final AtomicLong sourcesCreated=new AtomicLong(), sourcesDeleted=new AtomicLong(), sourcesRetired=new AtomicLong();
    private long uploadFence;
    private volatile boolean running=true, ready=false;
    private volatile String error="", renderer="", version="";
    private volatile long started=SystemClock.elapsedRealtime(), readyAt;
    private final AtomicLong reads=new AtomicLong(), draws=new AtomicLong();
    private final AtomicLong outputsCreated=new AtomicLong(), outputsDeleted=new AtomicLong();
    private final AtomicLong lastRead=new AtomicLong(), lastDraw=new AtomicLong();
    private final CountDownLatch published=new CountDownLatch(1), rasterReady=new CountDownLatch(1), rasterDone=new CountDownLatch(1);
    private final int[] sources=new int[COUNT];
    private EGLDisplay display;
    private EGLConfig config;
    private EGLContext uploadContext;
    public NativeExperiment(int mode,boolean fenceSync,boolean outputChurn,int sourcePipeline) {
        this.mode=mode; this.fenceSync=fenceSync; this.outputChurn=outputChurn; this.sourcePipeline=sourcePipeline;
        if(sourcePipeline!=0 && mode!=0)throw new IllegalArgumentException("source handoff requires concurrent mode");
    }
    public void start() {
        if(mode==0) new Thread(this::renderWorker,"gles-sampler").start();
        new Thread(this::uploadWorker,"gles-readback").start();
    }
    public void stop() { running=false; }
    private void fail(Throwable e) { error=e.toString(); running=false; }
    private static void check(String where) {
        int e=glGetError(); if(e!=GL_NO_ERROR) throw new IllegalStateException(where+" GL 0x"+Integer.toHexString(e));
    }
    private void egl(boolean ok,String where) {
        if(!ok) throw new IllegalStateException(where+" EGL 0x"+Integer.toHexString(EGL14.eglGetError()));
    }
    private EGLSurface surface() {
        EGLSurface s=EGL14.eglCreatePbufferSurface(display,config,new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
        egl(s!=EGL14.EGL_NO_SURFACE,"pbuffer");return s;
    }
    private EGLContext context(EGLContext share) {
        EGLContext c=EGL14.eglCreateContext(display,config,share,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        egl(c!=EGL14.EGL_NO_CONTEXT,"create context");return c;
    }
    private void detachAndDestroy(EGLContext c,EGLSurface s) {
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display,s);EGL14.eglDestroyContext(display,c);EGL14.eglReleaseThread();
    }
    private void setup() {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v=new int[2];egl(EGL14.eglInitialize(display,v,0,v,1),"initialize");
        EGLConfig[] configs=new EGLConfig[1];int[] n=new int[1];
        egl(EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,configs,0,1,n,0)&&n[0]>0,"config");
        config=configs[0];uploadContext=context(EGL14.EGL_NO_CONTEXT);
    }
    private void uploadTexture(int texture,int index,ByteBuffer pixels) {
        pixels.clear();for(int p=0;p<SIZE*SIZE;p++)pixels.put((byte)(index*7)).put((byte)93).put((byte)157).put((byte)255);pixels.flip();
        glBindTexture(GL_TEXTURE_2D,texture);glTexStorage2D(GL_TEXTURE_2D,9,GL_RGBA8,SIZE,SIZE);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glGenerateMipmap(GL_TEXTURE_2D);check("upload");glBindTexture(GL_TEXTURE_2D,0);
    }
    private void upload() {
        renderer=glGetString(GL_RENDERER);version=glGetString(GL_VERSION);
        if(sourcePipeline==2)return; // Uploads happen while both workers are active.
        glGenTextures(COUNT,sources,0);
        ByteBuffer pixels=ByteBuffer.allocateDirect(SIZE*SIZE*4);
        for(int i=0;i<COUNT;i++){uploadTexture(sources[i],i,pixels);sourcesCreated.incrementAndGet();}
        // Source texels and parameters remain immutable after publication.
        if(fenceSync) {
            uploadFence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if(uploadFence==0)throw new IllegalStateException("upload fence creation failed");
            glFlush();check("upload fence flush");
        } else {glFinish();check("upload finish");}
    }
    private static final class SourceJob {
        final int texture,index;final long fence;
        final CountDownLatch sampled=new CountDownLatch(1);
        SourceJob(int texture,int index,long fence){this.texture=texture;this.index=index;this.fence=fence;}
        void waitUpload(){glWaitSync(fence,0,GL_TIMEOUT_IGNORED);check("source handoff wait");}
    }
    private void releaseSourceJob(SourceJob job) {
        glDeleteSync(job.fence);
        if(sourcePipeline==2){glDeleteTextures(1,new int[]{job.texture},0);check("source retirement");sourcesDeleted.incrementAndGet();}
    }
    private void produceSources(int readFbo,ByteBuffer readBuffer)throws InterruptedException {
        ByteBuffer uploadPixels=ByteBuffer.allocateDirect(SIZE*SIZE*4);int index=0;
        while(running) {
            if(sourceJobs.size()==COUNT) {
                SourceJob oldest=sourceJobs.peekFirst();
                while(running && !oldest.sampled.await(100,TimeUnit.MILLISECONDS)){}
                if(!running)break;
                // This worker's readback already returned; sampler acknowledged after its readback.
                sourceJobs.removeFirst();releaseSourceJob(oldest);sourcesRetired.incrementAndGet();
            }
            int texture=sources[index];
            if(sourcePipeline==2){int[] x=new int[1];glGenTextures(1,x,0);texture=x[0];uploadTexture(texture,index,uploadPixels);sourcesCreated.incrementAndGet();}
            // Both cases use a fresh flushed fence and identical queue/backpressure protocol.
            long fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            if(fence==0)throw new IllegalStateException("source handoff fence creation failed");
            glFlush();check("source handoff flush");
            SourceJob job=new SourceJob(texture,index,fence);sourceJobs.addLast(job);sourceQueue.add(job);
            job.waitUpload();readTexture(readFbo,readBuffer,texture,index);
            // No FBO reference survives source retirement or numeric texture-name reuse.
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,0,0);check("source detach");
            index=(index+1)%COUNT;
        }
    }
    private void consumeSources(Sampler sampler)throws InterruptedException {
        while(running) {
            SourceJob job=sourceQueue.poll(100,TimeUnit.MILLISECONDS);if(job==null)continue;
            job.waitUpload();sampler.draw(job.texture,job.index);
            draws.incrementAndGet();lastDraw.set(SystemClock.elapsedRealtime());
            // draw ends with CPU readback and binds its own output, releasing the source binding.
            job.sampled.countDown();
        }
    }
    private void waitForUpload() {
        // Each context queues its own GPU wait. Neither consumes/deletes the fence.
        if(fenceSync){glWaitSync(uploadFence,0,GL_TIMEOUT_IGNORED);check("upload wait");}
    }
    private static int fbo() {int[] x=new int[1];glGenFramebuffers(1,x,0);return x[0];}
    private static void attach(int fbo,int texture) {
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("incomplete FBO");
    }
    private static void verify(ByteBuffer data,int i) {
        // Constant-color inputs give a deterministic independent correctness oracle.
        if((data.get(0)&255)!=i*7 || (data.get(1)&255)!=93 || (data.get(2)&255)!=157 || (data.get(3)&255)!=255)
            throw new IllegalStateException("pixel mismatch source="+i+" rgba="+(data.get(0)&255)+","+(data.get(1)&255)+","+(data.get(2)&255)+","+(data.get(3)&255));
    }
    private void read(int fbo,ByteBuffer buffer,int i) {
        readTexture(fbo,buffer,sources[i],i);
    }
    private void readTexture(int fbo,ByteBuffer buffer,int texture,int i) {
        attach(fbo,texture);buffer.clear();glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,buffer);check("source readback");verify(buffer,i);
        reads.incrementAndGet();lastRead.set(SystemClock.elapsedRealtime());
    }
    private void uploadWorker() {
        EGLSurface s=null;Sampler sampler=null;int readFbo=0;boolean peerStopped=mode!=0;
        try {
            setup();s=surface();egl(EGL14.eglMakeCurrent(display,s,s,uploadContext),"IO current");upload();
            readFbo=fbo();ByteBuffer buffer=ByteBuffer.allocateDirect(SIZE*SIZE*4);
            published.countDown();
            waitForUpload();
            if(mode==0) {
                if(!rasterReady.await(20,TimeUnit.SECONDS))throw new IllegalStateException("sampler initialization timeout");
                if(!error.isEmpty())return;
            } else sampler=new Sampler();
            readyAt=SystemClock.elapsedRealtime();lastRead.set(readyAt);lastDraw.set(readyAt);ready=true;
            if(sourcePipeline!=0){produceSources(readFbo,buffer);return;}
            int i=0;
            while(running) {
                read(readFbo,buffer,i);
                if(sampler!=null)sample(sampler,i);
                i=(i+1)%COUNT;
            }
        } catch(Throwable e) { fail(e); }
        finally {
            published.countDown();
            if(mode==0)try {peerStopped=rasterDone.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            // If another driver call hangs, do not destroy shared resources under it.
            if(peerStopped && s!=null) {
                if(sampler!=null)sampler.close();
                if(readFbo!=0)glDeleteFramebuffers(1,new int[]{readFbo},0);
                if(uploadFence!=0)glDeleteSync(uploadFence);
                while(!sourceJobs.isEmpty())releaseSourceJob(sourceJobs.removeFirst());
                glDeleteTextures(COUNT,sources,0);detachAndDestroy(uploadContext,s);EGL14.eglTerminate(display);
            }
        }
    }
    private void renderWorker() {
        EGLContext c=null;EGLSurface s=null;Sampler sampler=null;
        try {
            if(!published.await(30,TimeUnit.SECONDS))throw new IllegalStateException("upload publication timeout");
            if(!running)return;
            c=context(uploadContext);s=surface();egl(EGL14.eglMakeCurrent(display,s,s,c),"sampler current");
            waitForUpload();sampler=new Sampler();rasterReady.countDown();int i=0;
            while(running && !ready)Thread.sleep(1);
            if(sourcePipeline!=0)consumeSources(sampler);
            else while(running){sample(sampler,i);i=(i+1)%COUNT;}
        }catch(Throwable e){fail(e);}
        finally {
            rasterReady.countDown();
            if(sampler!=null)sampler.close();
            if(c!=null && s!=null)detachAndDestroy(c,s);
            rasterDone.countDown();
        }
    }
    private void sample(Sampler s,int i) {
        s.draw(sources[i],i);draws.incrementAndGet();lastDraw.set(SystemClock.elapsedRealtime());
    }
    private static int shader(int type,String source) {
        int x=glCreateShader(type);glShaderSource(x,source);glCompileShader(x);int[] ok=new int[1];glGetShaderiv(x,GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0)throw new IllegalStateException(glGetShaderInfoLog(x));return x;
    }
    private final class Sampler {
        final int program,fbo,vao;int output;final ByteBuffer pixels=ByteBuffer.allocateDirect(PATCH*PATCH*4);
        Sampler() {
            int vs=shader(GL_VERTEX_SHADER,"#version 300 es\nout vec2 uv;void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}");
            int fs=shader(GL_FRAGMENT_SHADER,"#version 300 es\nprecision highp float;in vec2 uv;uniform sampler2D src;out vec4 color;void main(){color=texture(src,uv);}");
            program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
            int[] ok=new int[1];glGetProgramiv(program,GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(glGetProgramInfoLog(program));
            glDeleteShader(vs);glDeleteShader(fs);glUseProgram(program);glUniform1i(glGetUniformLocation(program,"src"),0);
            int[] x=new int[1];glGenVertexArrays(1,x,0);vao=x[0];glBindVertexArray(vao);
            fbo=fbo();
            if(!outputChurn){createOutput();attach(fbo,output);}
            glDisable(GL_DITHER);check("sampler setup");
        }
        void createOutput() {
            int[] x=new int[1];glGenTextures(1,x,0);output=x[0];
            glBindTexture(GL_TEXTURE_2D,output);glTexStorage2D(GL_TEXTURE_2D,5,GL_RGBA8,PATCH,PATCH);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR_MIPMAP_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            check("output allocation");outputsCreated.incrementAndGet();
        }
        void deleteOutput() {
            if(output==0)return;
            // The bound FBO attachment and texture binding are detached by deletion.
            // Readback has completed; no other context ever uses this output.
            glDeleteTextures(1,new int[]{output},0);output=0;check("output deletion");outputsDeleted.incrementAndGet();
        }
        void draw(int source,int index) {
            if(outputChurn)createOutput();
            attach(fbo,output);glViewport(0,0,PATCH,PATCH);glUseProgram(program);glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,source);glDrawArrays(GL_TRIANGLES,0,3);
            // Output is local to this context; never alias the immutable source.
            glBindTexture(GL_TEXTURE_2D,output);glGenerateMipmap(GL_TEXTURE_2D);
            pixels.clear();glReadPixels(0,0,PATCH,PATCH,GL_RGBA,GL_UNSIGNED_BYTE,pixels);check("sample/read output");verify(pixels,index);
            if(outputChurn)deleteOutput();
        }
        void close(){glFinish();glDeleteFramebuffers(1,new int[]{fbo},0);deleteOutput();glDeleteProgram(program);glDeleteVertexArrays(1,new int[]{vao},0);}
    }
    public JSONObject snapshot() {
        long now=SystemClock.elapsedRealtime();JSONObject j=new JSONObject();
        try {
            long io=ready?now-lastRead.get():0, raster=ready?now-lastDraw.get():0;
            String state=!error.isEmpty()?"error":ready?(io>10000||raster>10000?"stalled":"running"):(now-started>30000?"setup_timeout":"starting");
            j.put("upload_sync",sourcePipeline!=0?"per_resource_fence":fenceSync?"fence":"finish");
            j.put("source_lifetime",sourcePipeline==2?"per_handoff":"persistent").put("source_schedule",sourcePipeline!=0?"bounded_handoff":"independent_loops");
            j.put("sources_created",sourcesCreated.get()).put("sources_deleted",sourcesDeleted.get()).put("sources_retired",sourcesRetired.get());
            j.put("output_lifetime",outputChurn?"per_iteration":"persistent").put("outputs_created",outputsCreated.get()).put("outputs_deleted",outputsDeleted.get());
            j.put("state",state).put("mode",mode==0?"concurrent":"serialized").put("elapsed_seconds",(now-started)/1000).put("readbacks",reads.get()).put("snapshots",draws.get()).put("io_stall_ms",io).put("raster_stall_ms",raster).put("gl_renderer",renderer).put("gl_version",version).put("error",error);
        }catch(Exception e){throw new IllegalStateException(e);}return j;
    }
}
