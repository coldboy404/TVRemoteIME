package com.android.tvremoteime.adb;

import android.content.Context;
import android.util.Log;

import com.android.tvremoteime.Environment;
import com.android.tvremoteime.R;
import com.android.tvremoteime.server.RemoteServer;
import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbCrypto;
import com.cgutman.adblib.AdbStream;
import com.cgutman.adblib.Base64;

import java.io.IOException;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;

/**
 * Created by kingt on 2018/3/7.
 */

public class AdbHelper {
    private static String TAG = "AdbHelper";
    private AdbConnection connection = null;
    private String host;
    private int port;

    private ArrayDeque<Object> sendDataDeque = new ArrayDeque<>();
    private Thread sendDataThread = null;
    // Written by stop()/init() on other threads, read in the sender
    // thread loop; must be volatile or the loop may never observe stop.
    private volatile boolean running = false;
    private Context context;

    private AdbHelper(){
    }

    public boolean isRunning(){
        return running;
    }

    public void init(Context context, String host, int port) {
        this.context = context;
        this.host = host;
        this.port = port;
        this.running = true;
        this.initSDThread();
    }

    public void stop() {
        this.running = false;
        this.context = null;
        if(this.connection != null){
            try {
                this.connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            this.connection = null;
        }
        synchronized (sendDataDeque) {
            sendDataDeque.notifyAll();
        }
        if(sendDataThread != null && sendDataThread.isAlive()) {
            sendDataThread.interrupt();
            try {
                sendDataThread.join();
            } catch (InterruptedException e) {
            }
        }
    }


    private void  initSDThread(){
        final AdbHelper adbHelper = this;
        if(sendDataThread == null || !sendDataThread.isAlive()){
            sendDataThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Object data = 0;
                    while (running) {
                        synchronized (sendDataDeque) {
                            try {
                                if(sendDataDeque.size() == 0)
                                    sendDataDeque.wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            if (sendDataDeque.size() > 0) {
                                data = sendDataDeque.pop();
                            }else{
                                continue;
                            }
                        }
                        String msg = null;
                        if(data instanceof Integer){
                            msg = "shell:input keyevent " + String.valueOf(data);
                        }else if(data instanceof ShellCommand){
                            msg = ((ShellCommand) data).command;
                        }else if(data instanceof String){
                            // Remote text ALWAYS goes through `input text`.
                            // The old code passed any string starting with
                            // "shell:" through as a raw ADB shell command,
                            // so anyone on the LAN could run arbitrary
                            // commands on the box via POST /text. Raw
                            // commands are now only available through
                            // sendShellCommand() (internal callers, whose
                            // arguments are validated integers).
                            msg = "shell:input text \"" + escapeForShell((String) data) + "\"";
                        }else{
                            continue;
                        }
                        try {
                            if(connection != null || try2Connect()){
                                AdbStream stream = connection.open(msg);
                                if(Environment.needDebug){
                                    Environment.debug(TAG, "已成功发送adb命令：" + msg);
                                    //Environment.toastInHandler(adbHelper.context, "TVRemoteIME成功向adb服务发送命令。"  + msg);
                                }
                            }else {
                                if(Environment.needDebug){
                                    Environment.debug(TAG, "未发送adb命令：" + msg);
                                    //Environment.toastInHandler(adbHelper.context, "TVRemoteIME向adb服务发送命令时失败。");
                                }
                            }
                            //stream.close();
                        } catch (Exception e) {
                            if(Environment.needDebug){
                                Environment.debug(TAG, "发送adb命令时出错：" + msg, e);
                                //Environment.toastInHandler(adbHelper.context, "TVRemoteIME向adb服务发送命令时出错。" + e.toString());
                            }
                        }
                    }
                }
            });
            sendDataThread.start();
        }
    }

    private boolean try2Connect()
    {
        try {
            Socket socket = new Socket(this.host, this.port);
            socket.setSoTimeout(1000 * 10);
            AdbCrypto crypto = null;
            try {
                crypto = AdbCrypto.generateAdbKeyPair(new AdbBase64() {
                    @Override
                    public String encodeToString(byte[] data) {
                        return Base64.encodeToString(data, 16);
                    }
                });
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            final AdbHelper adbHelper = this;
            if (connection == null) {
                connection = AdbConnection.create(socket, crypto);
                connection.setOnClosedListener(new AdbConnection.ConnectionOnClosedListener() {
                    @Override
                    public void onClosed() {
                        Log.i(TAG, "adb已断开连接。");
                        Environment.toastInHandler(adbHelper.context, context.getString(R.string.app_name)  + "和adb服务已断开连接。");
                        try {
                            connection.close();
                            connection = null;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

            connection.connect();
            Log.i(TAG, "adb已连接成功。");
            Environment.toastInHandler(adbHelper.context, context.getString(R.string.app_name)  + "和adb服务已连接成功。");
            return true;
        }catch(Exception e) {
            Log.e(TAG, "adb连接失败，错误信息。" + e.toString(), e);
            connection = null;
            return false;
        }
    }

    public void sendData(Object data){
        // ArrayDeque is not thread-safe: the push must happen under the
        // same lock as the wait/notify, otherwise concurrent HTTP
        // threads can corrupt the queue (and a notify can be missed).
        synchronized (sendDataDeque) {
            sendDataDeque.push(data);
            sendDataDeque.notifyAll();
        }
    }

    /** Wrapper that marks a string as a trusted raw shell command. */
    private static final class ShellCommand {
        final String command;
        ShellCommand(String command) {
            this.command = command;
        }
    }

    /**
     * Enqueue a raw ADB shell command. INTERNAL USE ONLY: callers must
     * build the command from validated values (the mouse fallback builds
     * tap/swipe commands from integers). Never pass text received from
     * the network here — remote text goes through sendData() and is
     * forced onto the `input text` path.
     */
    public void sendShellCommand(String command){
        if (command == null) return;
        sendData(new ShellCommand(command));
    }

    /**
     * Escape text for inclusion inside a double-quoted shell argument.
     * Backslash must be escaped first; the old code escaped quotes first
     * and then doubled every backslash, undoing its own quote escaping.
     * `$` and backticks are escaped too, since the device shell would
     * otherwise expand them inside double quotes.
     */
    static String escapeForShell(String text){
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static AdbHelper instance = null;
    public static AdbHelper getInstance(){
        return instance;
    }
    public static void createInstance(){
        if(instance == null){
            synchronized (AdbHelper.class){
                if(instance == null)
                    instance = new AdbHelper();
            }
        }
    }
public static boolean initService(Context context){
		if(instance != null){
			if(!instance.isRunning()){
				instance.init(context, RemoteServer.getLocalIPAddress(context), Environment.adbServerPort);
			}
			// Only return true if actually connected to ADB
			return instance.connection != null;
		}
		return false;
	}
    public static void stopService(){
        if(instance != null) instance.stop();
    }
}
