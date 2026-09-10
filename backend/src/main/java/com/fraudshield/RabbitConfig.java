package com.fraudshield;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * RabbitMQ topology for transaction ingestion. Only active when app.worker-mode=queue
 * (WORKER_MODE env var). In the default "poll" mode none of these beans exist, so the
 * application never opens a connection to a broker it may not have.
 *
 * Transactional-outbox pattern: FraudService.ingest() commits the transaction row and an
 * outbox_events row in the same database transaction; OutboxPublisher relays outbox rows to
 * this exchange. This avoids a database-to-broker dual-write gap.
 *
 * Bounded retry: a message that fails processing 3 times (RetryOperationsInterceptor) is
 * republished to the dead-letter exchange, landing in transactions.ingested.dlq, where
 * TransactionsDeadLetterConsumer marks the job DEAD and the transaction FAILED - mirroring the
 * existing DB-poll worker's bounded-retry/dead-letter behavior in RiskWorker.
 */
@Configuration
@ConditionalOnProperty(name="app.worker-mode",havingValue="queue")
public class RabbitConfig {
 static final String EXCHANGE="raksha.events";
 static final String DLX="raksha.events.dlx";
 static final String TRANSACTIONS_QUEUE="transactions.ingested";
 static final String TRANSACTIONS_DLQ="transactions.ingested.dlq";

 @Bean DirectExchange eventsExchange(){return new DirectExchange(EXCHANGE,true,false);}
 @Bean DirectExchange deadLetterExchange(){return new DirectExchange(DLX,true,false);}

 @Bean Queue transactionsIngestedQueue(){return QueueBuilder.durable(TRANSACTIONS_QUEUE).build();}
 @Bean Queue transactionsIngestedDlq(){return QueueBuilder.durable(TRANSACTIONS_DLQ).build();}
 @Bean Binding transactionsBinding(){return BindingBuilder.bind(transactionsIngestedQueue()).to(eventsExchange()).with(TRANSACTIONS_QUEUE);}
 @Bean Binding transactionsDlqBinding(){return BindingBuilder.bind(transactionsIngestedDlq()).to(deadLetterExchange()).with(TRANSACTIONS_QUEUE);}

 @Bean MessageConverter jsonMessageConverter(){
  // Publishers send Map.of(...) (an internal JDK Map subtype); listener parameters declare
  // Map<String,Object>. INFERRED precedence deserializes using the listener's declared type
  // instead of the sender's __TypeId__ header, which would otherwise name an unusable JDK class.
  Jackson2JsonMessageConverter converter=new Jackson2JsonMessageConverter();
  DefaultJackson2JavaTypeMapper typeMapper=new DefaultJackson2JavaTypeMapper();
  typeMapper.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.INFERRED);
  converter.setJavaTypeMapper(typeMapper);
  return converter;
 }

 @Bean RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,MessageConverter converter){
  RabbitTemplate template=new RabbitTemplate(connectionFactory);
  template.setMessageConverter(converter);
  return template;
 }

 @Bean RetryOperationsInterceptor transactionsRetryInterceptor(RabbitTemplate rabbitTemplate){
  return RetryInterceptorBuilder.stateless()
   .maxAttempts(3)
   .backOffOptions(2000,2.0,10000)
   .recoverer(new RepublishMessageRecoverer(rabbitTemplate,DLX,TRANSACTIONS_QUEUE))
   .build();
 }

 @Bean SimpleRabbitListenerContainerFactory transactionsListenerContainerFactory(
   ConnectionFactory connectionFactory,MessageConverter converter,RetryOperationsInterceptor transactionsRetryInterceptor){
  SimpleRabbitListenerContainerFactory factory=new SimpleRabbitListenerContainerFactory();
  factory.setConnectionFactory(connectionFactory);
  factory.setMessageConverter(converter);
  factory.setAdviceChain(transactionsRetryInterceptor);
  factory.setDefaultRequeueRejected(false);
  return factory;
 }
}
