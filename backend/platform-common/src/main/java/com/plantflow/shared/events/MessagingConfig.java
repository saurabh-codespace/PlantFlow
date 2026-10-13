package com.plantflow.shared.events;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@ConditionalOnProperty(name="plantflow.messaging.enabled",havingValue="true",matchIfMissing=true)
public class MessagingConfig {
    @Bean Declarables topology() {
        var exchange=new TopicExchange("plantflow.events",true,false);
        var dead=new DirectExchange("plantflow.dlx",true,false);
        List<Declarable> items=new ArrayList<>(List.of(exchange,dead));
        for(String name:List.of("transport","fleet","analytics","tracking")) {
            var queue=QueueBuilder.durable("plantflow."+name).deadLetterExchange("plantflow.dlx").deadLetterRoutingKey(name).build();
            var dlq=QueueBuilder.durable("plantflow."+name+".dlq").build();
            items.add(queue);items.add(dlq);items.add(BindingBuilder.bind(dlq).to(dead).with(name));
            var keys=switch(name) {
                case "transport" -> List.of("fleet.assignment-confirmed","fleet.assignment-rejected");
                case "fleet" -> List.of("transport.assignment-requested","transport.trip-changed");
                case "analytics" -> List.of("transport.trip-changed","fleet.availability-changed");
                default -> List.of("transport.trip-changed");
            };
            for(String key:keys) items.add(BindingBuilder.bind(queue).to(exchange).with(key));
        }
        return new Declarables(items);
    }
    @Bean RabbitTemplate rabbitTemplate(ConnectionFactory factory) { var template=new RabbitTemplate(factory);template.setMandatory(true);return template; }
    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connectionFactory) {
        var factory=new SimpleRabbitListenerContainerFactory();configurer.configure(factory,connectionFactory);
        factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);factory.setPrefetchCount(10);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxAttempts(4).backOffOptions(500,2,4000).recoverer(new RejectAndDontRequeueRecoverer()).build());
        return factory;
    }
}
