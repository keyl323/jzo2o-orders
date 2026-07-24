package com.jzo2o.orders.base.handler;

import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.statemachine.core.StatusChangeEvent;
import com.jzo2o.statemachine.core.StatusChangeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.ThrowsAdvice;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component("order_payed")
@Slf4j
public class OrderPayedHandler implements StatusChangeHandler<OrderSnapshotDTO> {

    @Resource
    private IOrdersCommonService ordersCommonService;
    @Override
    public void handler(String bizId, StatusChangeEvent statusChangeEventEnum, OrderSnapshotDTO bizSnapshot) {
        log.info("支付成功事件发布执行此动作");
        //统一对订单状态进行更新 将订单状态由待支付变为派单中
        OrderUpdateStatusDTO orderUpdateStatusDTO = new OrderUpdateStatusDTO();
        orderUpdateStatusDTO.setId(bizSnapshot.getId());
        orderUpdateStatusDTO.setOriginStatus(OrderStatusEnum.NO_SERVE.getStatus());//原始状态为待支付
        orderUpdateStatusDTO.setTargetStatus(OrderStatusEnum.DISPATCHING.getStatus());
        orderUpdateStatusDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());  //支付成功
        orderUpdateStatusDTO.setTradingOrderNo(bizSnapshot.getTradingOrderNo());
        orderUpdateStatusDTO.setTransactionId(bizSnapshot.getThirdOrderId());//第三方支付平台
        orderUpdateStatusDTO.setPayTime(bizSnapshot.getPayTime());//支付时间
        orderUpdateStatusDTO.setTradingChannel(bizSnapshot.getTradingChannel());

        Integer count = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (count < 1){
            throw new CommonException("支付成功事件执行动作失败");
        }
    }
}
