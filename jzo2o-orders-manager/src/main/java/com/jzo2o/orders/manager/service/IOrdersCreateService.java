package com.jzo2o.orders.manager.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import com.jzo2o.api.orders.dto.request.OrderCancelReqDTO;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;

import java.util.List;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author yutsung chen
 */
public interface IOrdersCreateService extends IService<Orders> {


    /**
     * 下单接口
     *
     * @param placeOrderReqDTO
     * @return
     */
    PlaceOrderResDTO placeOrder(PlaceOrderReqDTO placeOrderReqDTO);

    /**
     * 生成订单
     * @param orders
     */
    void add(Orders orders);

    /**
     * 订单支付
     *
     * @param id
     * @param ordersPayReqDTO
     * @return
     */
    OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO);

    /**
     * 请求支付服务查询结果
     * @param id
     * @return
     */
    OrdersPayResDTO getPayResultFromTradeServer(Long id);

    /**
     * 支付成功
     * @param tradeStatusMsg
     */
    void paySuccess(TradeStatusMsg tradeStatusMsg);


    /**
     * 查询支付超时订单id列表
     * @param count
     * @return
     */
    List<Orders> queryOverTimePayOrdersListByCount(Integer count);

    /**
     * 获取可用优惠券
     * @param serveId
     * @param purNum
     * @return
     */
    List<AvailableCouponsResDTO> getAvailableCoupons(Long serveId, Integer purNum);
}
