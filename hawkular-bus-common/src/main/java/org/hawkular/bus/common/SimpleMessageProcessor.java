package org.hawkular.bus.common;

import java.util.Map;

import javax.jms.JMSException;
import javax.jms.Message;

import org.hawkular.bus.common.consumer.AbstractBasicMessageListener;
import org.hawkular.bus.common.consumer.BasicMessageListener;
import org.hawkular.bus.common.consumer.ConsumerConnectionContext;
import org.hawkular.bus.common.consumer.RPCConnectionContext;
import org.hawkular.bus.common.producer.ProducerConnectionContext;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * A version of the MessageProcessor that keeps the passed interfaces internally.
 *
 * @author Heiko W. Rupp
 */
public class SimpleMessageProcessor extends MessageProcessor {

    private final ConsumerConnectionContext consumerCtx;
    private final ProducerConnectionContext producerCtx;

    public SimpleMessageProcessor(ConsumerConnectionContext consumerCtx, ProducerConnectionContext producerCtx) {
        this.consumerCtx = consumerCtx;
        this.producerCtx = producerCtx;
    }

    public <T extends BasicMessage> void listen(AbstractBasicMessageListener<T> listener) throws JMSException {
        super.listen(consumerCtx, listener);
    }

    public MessageId send(BasicMessage basicMessage) throws JMSException {
        return super.send(producerCtx, basicMessage);
    }

    public MessageId send(BasicMessage basicMessage, Map<String, String> headers) throws JMSException {
        return super.send(producerCtx, basicMessage, headers);
    }

    public <T extends BasicMessage> RPCConnectionContext sendAndListen(BasicMessage basicMessage, BasicMessageListener<T> responseListener) throws JMSException {
        return super.sendAndListen(producerCtx, basicMessage, responseListener);
    }

    public <T extends BasicMessage> RPCConnectionContext sendAndListen(BasicMessage basicMessage, BasicMessageListener<T> responseListener,
            Map<String, String> headers) throws JMSException {
        return super.sendAndListen(producerCtx, basicMessage, responseListener, headers);
    }

    public <R extends BasicMessage> ListenableFuture<R> sendRPC(BasicMessage basicMessage, Class<R> expectedResponseMessageClass) throws JMSException {
        return super.sendRPC(producerCtx, basicMessage, expectedResponseMessageClass);
    }

    public <R extends BasicMessage> ListenableFuture<R> sendRPC(BasicMessage basicMessage, Class<R> expectedResponseMessageClass, Map<String, String> headers)
            throws JMSException {
        return super.sendRPC(producerCtx, basicMessage, expectedResponseMessageClass, headers);
    }

    protected Message createMessage(ConnectionContext context, BasicMessage basicMessage) throws JMSException {
        return super.createMessage(context, basicMessage);
    }

    protected Message createMessage(ConnectionContext context, BasicMessage basicMessage, Map<String, String> headers) throws JMSException {
        return super.createMessage(context, basicMessage, headers);
    }
}
