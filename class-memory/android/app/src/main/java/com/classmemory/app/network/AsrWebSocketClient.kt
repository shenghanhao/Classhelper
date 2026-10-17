package com.classmemory.app.network

import com.classmemory.app.i18n.tr

import kotlinx.coroutines.CompletableDeferred
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
class AsrWebSocketClient(private val url:String,private val uid:String,private val generation:Long,private val callbacks:Callbacks){
 interface Callbacks{fun ready(generation:Long);fun segment(generation:Long,segment:AsrSegment);fun protocolError(generation:Long,message:String);fun closed(generation:Long,error:String?)}
 private val client=OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).build(); private val ready=CompletableDeferred<Boolean>();private var socket:WebSocket?=null;private val closedNotified=AtomicBoolean(false)
 fun connect(){runCatching{socket=client.newWebSocket(Request.Builder().url(url).build(),listener())}.onFailure{fail(it.message?:"WebSocket connect failed")}}
 private fun listener()=object:WebSocketListener(){
  override fun onOpen(ws:WebSocket,response:Response){if(!ws.send(AsrProtocol.hello(uid)))fail("Handshake send failed")}
  override fun onMessage(ws:WebSocket,text:String){parse(text)}
  override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){fail(t.message?:"WebSocket failure")}
  override fun onClosed(ws:WebSocket,code:Int,reason:String){if(!ready.isCompleted)ready.complete(false);notifyClosed(reason.ifBlank{null})}
 }
 private fun notifyClosed(error:String?){if(closedNotified.compareAndSet(false,true))callbacks.closed(generation,error)}
 private fun fail(message:String){if(!ready.isCompleted)ready.complete(false);socket?.close(1002,"protocol error");notifyClosed(message)}
 private fun parse(text:String){runCatching{AsrProtocol.parseServerMessage(text,uid)}.onSuccess{event -> when(event){ServerMessage.Ready -> if(!ready.isCompleted){ready.complete(true);callbacks.ready(generation)};is ServerMessage.Wait -> {callbacks.protocolError(generation,tr("服务器等待：${event.message}","Server waiting: ${event.message}"));fail(tr("服务器未就绪","Server is not ready"))};is ServerMessage.Error -> {callbacks.protocolError(generation,event.message);fail(event.message)};is ServerMessage.Segments -> event.items.forEach{callbacks.segment(generation,it)}}}.onFailure{callbacks.protocolError(generation,tr("服务器响应格式无效","Invalid server response format"));fail(tr("服务器响应格式无效","Invalid server response format"))}}
 suspend fun awaitReady():Boolean=ready.await()
 fun sendAudio(bytes:ByteArray):Boolean{if(socket?.queueSize()?.let{it>1_048_576}==true)return false;return runCatching{socket?.send(ByteString.of(*bytes))==true}.getOrDefault(false)}
 fun finish(){runCatching{socket?.send(ByteString.of(*AsrProtocol.END_OF_AUDIO))}}
 suspend fun closeGracefully(timeoutMs:Long){runCatching{socket?.send(ByteString.of(*AsrProtocol.END_OF_AUDIO));socket?.close(1000,"client stop");kotlinx.coroutines.delay(timeoutMs)};socket?.cancel();client.dispatcher.executorService.shutdown()}
 fun cancel(){socket?.cancel();client.dispatcher.executorService.shutdown()}
}
