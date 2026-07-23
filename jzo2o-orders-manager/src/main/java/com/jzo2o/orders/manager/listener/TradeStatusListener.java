package com.jzo2o.orders.manager.listener;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.constants.MqConstants;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import groovy.util.logging.Slf4j;
import jakarta.json.Json;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.xml.validation.Validator;
import java.util.List;
import java.util.stream.Collectors;

@lombok.extern.slf4j.Slf4j
@Slf4j
@Component
public class TradeStatusListener {

    @Resource
    private IOrdersCreateService ordersCreateService;


    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.Queues.ORDERS_TRADE_UPDATE_STATUS),
            exchange = @Exchange(name = MqConstants.Exchanges.TRADE,type = ExchangeTypes.TOPIC),
            key = MqConstants.RoutingKeys.TRADE_UPDATE_STATUS
    ))
    public void listenTradeUpdatePayStatusMsg(String msg){
        log.info("接收到支付结果状态的消息({})->{}",MqConstants.Queues.ORDERS_TRADE_UPDATE_STATUS,msg);
        List<TradeStatusMsg> tradeStatusMsgs = JSON.parseArray(msg, TradeStatusMsg.class);

        List<TradeStatusMsg> msgList = tradeStatusMsgs.stream().filter(v->v.getStatusCode().equals(TradingStateEnum.YJS.getCode()) && "jzo2o.orders".equals(v.getProductAppId())).collect(Collectors.toList());
        if (CollUtil.isEmpty(msgList)){
            return;
        }

        msgList.forEach(m-> ordersCreateService.paySuccess(m));
    }
}
