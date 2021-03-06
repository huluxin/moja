package com.song.moja.netty;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson.JSON;
import com.song.moja.log.LogConfig;
import com.song.moja.persistent.PersistThread;
import com.song.moja.serialize.Serialization;
import com.song.moja.server.ServerConfig;
import com.song.moja.server.ThreadManager;
import com.song.moja.util.PropertyUtil;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;

/**
 * 负责JSON格式的日志的逻辑处理
 * @author 3gods.com
 *
 * @param <T>
 */
public class NettyServerHandler<T> extends SimpleChannelInboundHandler<T> {

	final ThreadManager<T> threadManager;
	final ServerConfig config;
	final long enqueueTimeoutMs;

	private HttpRequest request;

	private ResultMsgConfig resultMsgConfig;

	final int persistBatchSize;

	final List<T> tempList;
	
	final int maxMessageSize;
	
	public NettyServerHandler(ThreadManager threadManager, ServerConfig config) {
		this.threadManager = threadManager;
		this.config = config;
		
		this.enqueueTimeoutMs = config.getEnqueueTimeoutMs();
		this.resultMsgConfig = new ResultMsgConfig(config.props);
		this.persistBatchSize = config.getPersistBatchSize();
		this.tempList = new ArrayList<T>(persistBatchSize);
		this.maxMessageSize = config.getMaxMessageSize();
	}

	//这种是JSON格式的
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object obj)
			throws Exception {
		byte[] bys = Serialization.serialize(obj);
		if(bys.length>maxMessageSize){
			throw new IllegalArgumentException("客户端发送的消息过大!!!长度是:"+bys.length);
		}
		
		T data = (T) obj;
		BlockingQueue<T> queue = threadManager.getMq();
		boolean added = false;
		// 将JSON转换成实体类
		if (data != null) {
			try {
				if (enqueueTimeoutMs == 0) {
					added = queue.offer(data);
				} else if (enqueueTimeoutMs < 0) {
					queue.put(data);
					added = true;
				} else {
					added = queue.offer(data, enqueueTimeoutMs,
							TimeUnit.MILLISECONDS);
				}
			} catch (InterruptedException e) {
				throw new InterruptedException();
			}
		}
		ResultMsg resultMsg = new ResultMsg();
		// 这里返回给对方的消息，由谁定义了？
		if (added) {
			resultMsg.setErrCode(resultMsgConfig.getSuccCode());
			resultMsg.setErrMsg(resultMsgConfig.getSuccMsg());
		} else if (!added) {

			int drainResult = queue.drainTo(tempList, persistBatchSize);
			new PersistThread<T>(queue,tempList,new LogConfig(config.props)).start();
			tempList.clear();
		}
		// 返回结果
		//将对象转化成json，然后返回
		String resultMsgStr = JSON.toJSONString(resultMsg, true);
		ctx.channel().writeAndFlush(resultMsgStr.getBytes());
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
		cause.printStackTrace();
		ctx.close(); 
	}

	protected void messageReceived(ChannelHandlerContext arg0, Object arg1)
			throws Exception {
		System.out.println("messageReceived" + arg1);
	}
}