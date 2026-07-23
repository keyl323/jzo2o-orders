package com.jzo2o.orders.manager.handler;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import co.elastic.clients.elasticsearch.watcher.ExecutionResult;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jzo2o.api.trade.RefundRecordApi;
import com.jzo2o.api.trade.dto.response.ExecutionResultResDTO;
import com.jzo2o.api.trade.enums.RefundStatusEnum;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.model.dto.request.OrdersSeizeReqDTO;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.apache.bcel.generic.ObjectType;
import org.aspectj.weaver.ast.Or;
import org.checkerframework.common.value.qual.IntRangeFromGTENegativeOne;
import org.elasticsearch.search.DocValueFormat;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Random;


@Component
@Slf4j
public class OrdersHandler {
    @Resource
    private IOrdersCreateService ordersCreateService;
    @Resource
    private IOrdersManagerService ordersManagerServicel;
    @Resource
    private IOrdersRefundService ordersRefundService;
    @Resource
    private RefundRecordApi refundRecordApi;
    @Resource
    private OrdersMapper ordersMapper;
    @Resource
    private OrdersHandler ordersHandler;

    @XxlJob("cancelOverTimePayOrder")
    public void cancelOverTimePayOrder(){
        log.info("取消超时未支付订单任务执行");
        List<Orders> orders = ordersCreateService.queryOverTimePayOrdersListByCount(100);
        if (CollUtil.isEmpty(orders)){
            log.info("无超时未支付订单");
            return;
        }

        for (Orders order : orders) {
            OrderCancelDTO orderCancelDTO = BeanUtil.toBean(order, OrderCancelDTO.class);
            orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
            orderCancelDTO.setCancelReason("订单超时支付,系统自动取消");
            ordersManagerServicel.cancel(orderCancelDTO);
        }

    }


    @XxlJob("handleRefundOrders")
    public void handleRefundOrders(){
        //取出退款记录
        List<OrdersRefund> ordersRefunds = ordersRefundService.queryRefundOrderListByCount(100);
        if (CollUtil.isEmpty(ordersRefunds)){
            log.info("无退款订单");
            return;
        }
        for (OrdersRefund ordersRefund : ordersRefunds) {
            //请求退款
            requestRefundOrder(ordersRefund);
        }

    }

    //请求退款
    public void requestRefundOrder(OrdersRefund ordersRefund) {
        ExecutionResultResDTO executionResultResDTO =null;
        try {
            executionResultResDTO = refundRecordApi.refundTrading(ordersRefund.getTradingOrderNo(), ordersRefund.getRealPayAmount());
        }catch (Exception e){
            e.printStackTrace();
        }
        if (executionResultResDTO != null){
            //退款后处理订单相关信息
            ordersHandler.refundOrder(ordersRefund,executionResultResDTO);
        }

    }


    //更新退款状态
    @Transactional(rollbackFor = Exception.class)
    public void refundOrder(OrdersRefund ordersRefund, ExecutionResultResDTO executionResultResDTO) {
        int refundStatus = OrderRefundStatusEnum.REFUNDING.getStatus();
        if (ObjectUtils.equal(RefundStatusEnum.SUCCESS.getCode(),executionResultResDTO.getRefundStatus())){
            //退款成功
            refundStatus = OrderRefundStatusEnum.REFUND_SUCCESS.getStatus();
        }else if(ObjectUtils.equal(RefundStatusEnum.FAIL.getCode(),executionResultResDTO.getRefundStatus())){
            //退款失败
            refundStatus = OrderRefundStatusEnum.REFUND_FAIL.getStatus();
        }

        //如果是退款中 程序结束
        if (ObjectUtils.equals(refundStatus,OrderRefundStatusEnum.REFUNDING.getStatus())){
            return;
        }

        LambdaUpdateWrapper<Orders> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Orders::getId,ordersRefund.getId())
                .ne(Orders::getRefundStatus,refundStatus)
                .set(Orders::getRefundStatus,refundStatus)
                .set(ObjectUtils.isNotEmpty(executionResultResDTO.getRefundId()),Orders::getRefundId,executionResultResDTO.getRefundId())
                .set(ObjectUtils.isNotEmpty(executionResultResDTO.getRefundNo()),Orders::getRefundNo,executionResultResDTO.getRefundNo());
        int update = ordersMapper.update(null, wrapper);
        //非退款中状态删除申请退款记录 删除后定时任务不再扫描
        if (update > 0){
            //非退款中状态 删除申请退款记录 删除后定时任务不再扫描
            ordersRefundService.removeById(ordersRefund.getId());
        }

    }

    /**
     * 新启动一个线程请求退款
     * @param ordersRefundId
     */
    public void requestRefundNewThread(Long ordersRefundId){
        new Thread(() -> {
            OrdersRefund ordersRefund = ordersRefundService.getById(ordersRefundId);
            if (ObjectUtils.isNotNull(ordersRefund)){
                requestRefundOrder(ordersRefund);
            }
        }).start();
    }

}
