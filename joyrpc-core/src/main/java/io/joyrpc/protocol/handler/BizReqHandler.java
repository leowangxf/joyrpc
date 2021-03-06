package io.joyrpc.protocol.handler;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.Result;
import io.joyrpc.codec.compression.Compression;
import io.joyrpc.constants.ExceptionCode;
import io.joyrpc.context.RequestContext;
import io.joyrpc.context.injection.RespInjection;
import io.joyrpc.context.injection.Transmit;
import io.joyrpc.exception.*;
import io.joyrpc.invoker.Exporter;
import io.joyrpc.invoker.InvokerManager;
import io.joyrpc.protocol.MessageHandler;
import io.joyrpc.protocol.MsgType;
import io.joyrpc.protocol.message.*;
import io.joyrpc.transport.channel.Channel;
import io.joyrpc.transport.channel.ChannelContext;
import io.joyrpc.transport.session.DefaultSession;
import io.joyrpc.transport.session.Session;
import io.joyrpc.transport.transport.ChannelTransport;
import io.joyrpc.util.network.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static io.joyrpc.Plugin.RESPONSE_INJECTION;
import static io.joyrpc.Plugin.TRANSMIT;
import static io.joyrpc.constants.ExceptionCode.PROVIDER_TASK_SESSION_EXPIRED;
import static io.joyrpc.util.ClassUtils.forName;
import static io.joyrpc.util.ClassUtils.getPublicMethod;

/**
 * @date: 2019/3/14
 */
public class BizReqHandler extends AbstractReqHandler implements MessageHandler {

    private final static Logger logger = LoggerFactory.getLogger(BizReqHandler.class);

    /**
     * 透传
     */
    protected Iterable<Transmit> transmits = TRANSMIT.extensions();
    /**
     * 应答注入
     */
    protected Iterable<RespInjection> injections = RESPONSE_INJECTION.extensions();

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void handle(final ChannelContext context, final Message message) throws HandlerException {
        if (!(message instanceof RequestMessage)) {
            return;
        }
        RequestMessage<Invocation> request = (RequestMessage<Invocation>) message;
        //绑定上下文
        request.setContext(RequestContext.getContext());
        Invocation invocation = request.getPayLoad();
        Channel channel = context.getChannel();

        if (request.isTimeout(request::getReceiveTime)) {
            // 客户端已经超时的请求
            logger.warn(ExceptionCode.format(ExceptionCode.PROVIDER_DISCARD_TIMEOUT_MESSAGE)
                    + "Discard request cause by timeout after receive the msg: {}", request.getHeader());
            return;
        } else if (!channel.isWritable()) {
            //channel不可写，丢弃消息
            logger.error(String.format("Discard request, because client is sending too fast, causing channel is not writable. at %s : %s",
                    Channel.toString(channel), request.getHeader()));
            return;
        }

        Exporter exporter = null;
        try {
            //从会话恢复
            restore(request, channel);
            //根据请求参数获取输出的服务，依赖于会话恢复的信息
            exporter = InvokerManager.getExporter(invocation.getClassName(), invocation.getAlias(), channel.getLocalAddress().getPort());
            if (exporter == null) {
                //如果本地没有该服务，抛出ShutdownExecption，让消费者主动关闭连接
                throw new ShutdownExecption(error(invocation, channel, " exporter is not found"));
            }

            ChannelTransport transport = channel.getAttribute(Channel.CHANNEL_TRANSPORT);
            InvokerManager.getProducerCallback().addCallback(request, transport);
            //执行调用，包括过滤器链
            CompletableFuture<Result> future = exporter.invoke(request);

            final Exporter service = exporter;
            future.whenComplete((r, throwable) -> onComplete(r, throwable, request, service, channel));

        } catch (ClassNotFoundException e) {
            sendException(channel, new RpcException(error(invocation, channel, e.getMessage())), request, null);
        } catch (NoSuchMethodException e) {
            sendException(channel, new RpcException(error(invocation, channel, e.getMessage())), request, null);
        } catch (LafException e) {
            sendException(channel, e, request, exporter);
        } catch (Exception e) {
            sendException(channel, new RpcException(error(invocation, channel, e.getMessage()), e), request, exporter);
        } finally {
            //清理上下文
            RequestContext.remove();
        }
    }

    /**
     * 调用完成
     *
     * @param result
     * @param throwable
     * @param request
     * @param exporter
     * @param channel
     */
    protected void onComplete(final Result result,
                              final Throwable throwable,
                              final RequestMessage<Invocation> request,
                              final Exporter exporter,
                              final Channel channel) {
        Invocation invocation = request.getPayLoad();
        if (throwable != null) {
            if (!(throwable instanceof ShutdownExecption)) {
                logger.error(error(invocation, channel, throwable.getMessage()));
            }
            sendException(channel, throwable, request, exporter);
            return;
        }
        //构造响应Msg
        MessageHeader header = request.getHeader();
        Session session = request.getSession();
        Supplier<ResponseMessage> supplier = request.getResponseSupplier();
        ResponseMessage<ResponsePayload> response = supplier != null ? supplier.get() :
                new ResponseMessage<>(header.response(MsgType.BizResp.getType(),
                        session == null ? Compression.NONE : session.getCompressionType()));
        if (result.getContext().isAsync() && !result.isException()) {
            //异步
            ((CompletableFuture<Object>) result.getValue()).whenComplete((obj, th) -> {
                response.setPayLoad(new ResponsePayload(obj, th));
                channel.send(response, sendFailed);
            });
        } else {
            response.setPayLoad(new ResponsePayload(result.getValue(), result.getException()));
            channel.send(response, sendFailed);
        }
    }

    /**
     * 补充信息
     *
     * @param request
     * @param channel
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException
     * @throws MethodOverloadException
     */
    protected void restore(final RequestMessage<Invocation> request, final Channel channel)
            throws ClassNotFoundException, NoSuchMethodException, MethodOverloadException {
        DefaultSession session = (DefaultSession) request.getSession();
        Invocation invocation = request.getPayLoad();
        transmits.forEach(o -> o.restore(request, session));
        invocation.apply(session);
        //类名，如果不存在则从会话里面获取
        String className = invocation.getClassName();
        if (className == null || className.isEmpty()) {
            //session 为空，类名也为空，可能是session超时并被清理
            throw new SessionException(error(invocation, channel, " session has been cleared, may be the session has expired",
                    PROVIDER_TASK_SESSION_EXPIRED));
        }
        //检查接口ID，兼容老版本
        checkInterfaceId(invocation, className);
        //处理调用类
        if (invocation.getClazz() == null) {
            invocation.setClazz(forName(invocation.getClassName()));
        }
        //处理调用方法
        if (invocation.getMethod() == null) {
            invocation.setMethod(getPublicMethod(invocation.getClassName(), invocation.getMethodName()));
        }
        request.setLocalAddress(channel.getLocalAddress());
        request.setRemoteAddress(channel.getRemoteAddress());
        request.getContext().setLocalAddress(channel.getLocalAddress());
        request.getContext().setRemoteAddress(channel.getRemoteAddress());
    }

    /**
     * 检查接口ID，兼容老版本
     *
     * @param invocation
     * @param className
     * @throws ClassNotFoundException
     */
    protected void checkInterfaceId(final Invocation invocation, String className) throws ClassNotFoundException {
        if (Character.isDigit(className.charAt(0))) {
            //处理接口ID，兼容老版本调用
            try {
                className = InvokerManager.getClassName(Long.parseLong(className));
                if (className == null) {
                    throw new ClassNotFoundException("class is not found by interfaceId " + invocation.getClassName());
                }
                invocation.setClassName(className);
                invocation.setClazz(null);
            } catch (NumberFormatException e) {
                throw new ClassNotFoundException("class is not found by interfaceId " + invocation.getClassName());
            }
        }
    }

    /**
     * 获取错误信息
     *
     * @param request
     * @param channel
     * @param cause
     * @return
     */
    protected String error(final Invocation request, final Channel channel, final String cause) {
        return error(request, channel, cause, ExceptionCode.PROVIDER_TASK_FAIL);
    }

    /**
     * 获取错误信息
     *
     * @param request
     * @param channel
     * @param cause
     * @param code
     * @return
     */
    protected String error(final Invocation request, final Channel channel, final String cause, final String code) {
        return String.format(ExceptionCode.format(code == null ? ExceptionCode.PROVIDER_TASK_FAIL : code)
                        + "Error occurs while processing request %s/%s/%s from channel %s->%s, caused by: %s",
                request.getClassName(),
                request.getMethodName(),
                request.getAlias(),
                Ipv4.toAddress(channel.getRemoteAddress()),
                Ipv4.toAddress(channel.getLocalAddress()),
                cause);
    }

    /**
     * @param channel
     * @param ex
     * @param request
     * @param exporter 发送异常信息
     */
    protected void sendException(final Channel channel, final Throwable ex, final RequestMessage request, final Exporter exporter) {
        //构建异常应答消息，不压缩
        ResponseMessage response = new ResponseMessage(
                request.getHeader().response(
                        MsgType.BizResp.getType(), Compression.NONE),
                new ResponsePayload(ex));
        //注入异常信息
        inject(request, response, exporter);
        channel.send(response, sendFailed);
    }

    /**
     * 注入应答
     *
     * @param request
     * @param response
     * @param exporter
     */
    protected void inject(final RequestMessage request, final ResponseMessage<ResponsePayload> response, final Exporter exporter) {
        for (RespInjection injection : injections) {
            injection.inject(request, response, exporter);
        }
    }

    @Override
    public Integer type() {
        return (int) MsgType.BizReq.getType();
    }
}
