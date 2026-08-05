package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.db.DbRuntimeException;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.market.CouponApi;
import com.jzo2o.api.market.dto.request.CouponUseBackReqDTO;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.OrderSimpleResDTO;
import com.jzo2o.api.trade.enums.PayChannelEnum;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.enums.EnableStatusEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.common.utils.JsonUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.handler.OrderCancelHandler;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.handler.OrdersHandler;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.model.dto.request.OrderServeCancelReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.service.IOrdersCanceledService;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.jzo2o.redis.helper.CacheHelper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.weaver.ast.Or;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.jzo2o.orders.base.constants.FieldConstants.SORT_BY;
import static com.jzo2o.orders.base.constants.RedisConstants.RedisKey.ORDERS;

/**
 * <p>
 * 订单表 服务实现类
 * </p>
 *
 * @author yutsung chen
 */
@Slf4j
@Service
public class OrdersManagerServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersManagerService {

    @Resource
    private OrdersManagerServiceImpl owner;

    @Resource
    private IOrdersCanceledService ordersCanceledService;

    @Resource
    private IOrdersCommonService ordersCommonService;
    @Resource
    private IOrdersCreateService ordersCreateService;
    @Resource
    private IOrdersRefundService ordersRefundService;
    @Resource
    private OrdersHandler ordersHandler;
    @Resource
    private OrderStateMachine orderStateMachine;
    @Resource
    private CacheHelper cacheHelper;
    @Resource
    private CouponApi couponApi;

    @Override
    public List<Orders> batchQuery(List<Long> ids) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery().in(Orders::getId, ids);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public Orders queryById(Long id) {
        return baseMapper.selectById(id);
    }

    /**
     * 滚动分页查询
     *
     * @param currentUserId 当前用户id
     * @param ordersStatus  订单状态，0：待支付，100：派单中，200：待服务，300：服务中，400：待评价，500：订单完成，600：已取消，700：已关闭
     * @param sortBy        排序字段
     * @return 订单列表
     */
    @Override
    public List<OrderSimpleResDTO> consumerQueryList(Long currentUserId, Integer ordersStatus, Long sortBy) {
        //1.构件查询条件
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
                .eq(ObjectUtils.isNotNull(ordersStatus), Orders::getOrdersStatus, ordersStatus)
                .lt(ObjectUtils.isNotNull(sortBy), Orders::getSortBy, sortBy)
                .eq(Orders::getUserId, currentUserId)
                .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus())
                .select(Orders::getId);//指定查询的字段是订单id
        Page<Orders> queryPage = new Page<>();
        queryPage.addOrder(OrderItem.desc(SORT_BY));
        queryPage.setSearchCount(false);

        //2.查询订单列表 使用覆盖索引
        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
        //将查询出的列表提取出订单id
        List<Orders> orderList = ordersPage.getRecords();
        List<Long> ids = CollUtils.getFieldValues(orderList, Orders::getId);
        //根据订单id查询
        //List<Orders> ordersList = batchQuery(ids);
        String redisKey = String.format(ORDERS, currentUserId);
        //将订单信息存入缓存 如果有缓存直接查缓存
        List<OrderSimpleResDTO> orderSimpleResDTOS = cacheHelper.<Long, OrderSimpleResDTO>batchGet(redisKey, ids, (noCacheIds, clazz) -> {
            List<Orders> ordersList = batchQuery(noCacheIds);
            Map<Long, OrderSimpleResDTO> collect = ordersList.stream().collect(Collectors.toMap(Orders::getId, o -> BeanUtils.toBean(o, OrderSimpleResDTO.class)));
            return collect;
        }, OrderSimpleResDTO.class, 600L);
        //List<OrderSimpleResDTO> orderSimpleResDTOS = BeanUtil.copyToList(ordersList, OrderSimpleResDTO.class);
        return orderSimpleResDTOS;

    }

//    /**
//     * 滚动分页查询
//     *
//     * @param currentUserId 当前用户id
//     * @param ordersStatus  订单状态，0：待支付，100：派单中，200：待服务，300：服务中，400：待评价，500：订单完成，600：已取消，700：已关闭
//     * @param sortBy        排序字段
//     * @return 订单列表
//     */
//    @Override
//    public List<OrderSimpleResDTO> consumerQueryList(Long currentUserId, Integer ordersStatus, Long sortBy) {
//        //1.构件查询条件
//        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
//                .eq(ObjectUtils.isNotNull(ordersStatus), Orders::getOrdersStatus, ordersStatus)
//                .lt(ObjectUtils.isNotNull(sortBy), Orders::getSortBy, sortBy)
//                .eq(Orders::getUserId, currentUserId)
//                .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus())
//        Page<Orders> queryPage = new Page<>();
//        queryPage.addOrder(OrderItem.desc(SORT_BY));
//        queryPage.setSearchCount(false);
//
//        //2.查询订单列表
//        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
//        List<Orders> records = ordersPage.getRecords();
//        List<OrderSimpleResDTO> orderSimpleResDTOS = BeanUtil.copyToList(records, OrderSimpleResDTO.class);
//        return orderSimpleResDTOS;
//
//    }

    /**
     * 根据订单id查询
     *
     * @param id 订单id
     * @return 订单详情
     */
    @Override
    public OrderResDTO getDetail(Long id) {
//        Orders orders = queryById(id);
        //查询订单快照
        String currentSnapshotCache = orderStateMachine.getCurrentSnapshotCache(id.toString());
        //将json转成对象
        OrderSnapshotDTO orderSnapshotDTO = JsonUtils.toBean(currentSnapshotCache, OrderSnapshotDTO.class);
        orderSnapshotDTO = cancelIfPayOverTime(orderSnapshotDTO);
        OrderResDTO orderResDTO = BeanUtil.toBean(orderSnapshotDTO, OrderResDTO.class);
        return orderResDTO;
    }

    /**
     * 超过时间取消订单
     *
     * @param orderSnapshotDTO
     * @return
     */
    public OrderSnapshotDTO cancelIfPayOverTime(OrderSnapshotDTO orderSnapshotDTO) {
        Integer orderStatus = orderSnapshotDTO.getOrdersStatus();
        //创建订单未支付15分钟后自动取消
        if (orderStatus == OrderStatusEnum.NO_PAY.getStatus()
                && orderSnapshotDTO.getCreateTime().isBefore(LocalDateTime.now().minusMinutes(15))) {
            //查询支付结果 如果支付状态仍然是未支付进行取消订单
            OrdersPayResDTO ordersPayResDTO = ordersCreateService.getPayResultFromTradeServer(orderSnapshotDTO.getId());
            int payResultFromTradServer = ordersPayResDTO.getPayStatus();
            if (payResultFromTradServer != OrderPayStatusEnum.PAY_SUCCESS.getStatus()) {
                OrderCancelDTO orderCancelDTO = BeanUtils.toBean(orderSnapshotDTO, OrderCancelDTO.class);
                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
                orderCancelDTO.setCancelReason("订单超时支付,自动取消");
                cancelByNoPay(orderCancelDTO);

                String currentSnapshot = orderStateMachine.getCurrentSnapshotCache(orderSnapshotDTO.getId().toString());
                orderSnapshotDTO = JsonUtils.toBean(currentSnapshot, OrderSnapshotDTO.class);
                return orderSnapshotDTO;
            }
        }
        return orderSnapshotDTO;
    }

//    /**
//     * 超过时间取消订单
//     * @param orders
//     * @return
//     */
//    public Orders cancelIfPayOverTime(Orders orders) {
//        //创建订单未支付15分钟后自动取消
//        if (orders.getOrdersStatus()==OrderStatusEnum.NO_PAY.getStatus()
//                && orders.getCreateTime().isBefore(LocalDateTime.now().minusMinutes(15))){
//            //查询支付结果 如果支付状态仍然是未支付进行取消订单
//            OrdersPayResDTO ordersPayResDTO = ordersCreateService.getPayResultFromTradeServer(orders.getId());
//            int payResultFromTradServer = ordersPayResDTO.getPayStatus();
//            if (payResultFromTradServer != OrderPayStatusEnum.PAY_SUCCESS.getStatus()){
//                OrderCancelDTO orderCancelDTO = BeanUtils.toBean(orders, OrderCancelDTO.class);
//                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
//                orderCancelDTO.setCancelReason("订单超时支付,自动取消");
//                cancel(orderCancelDTO);
//                orders = getById(orders.getId());
//            }
//        }
//        return orders;
//    }

    /**
     * 订单评价
     *
     * @param ordersId 订单id
     */
    @Override
    @Transactional
    public void evaluationOrder(Long ordersId) {
//        //查询订单详情
//        Orders orders = queryById(ordersId);
//
//        //构建订单快照
//        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
//                .evaluationTime(LocalDateTime.now())
//                .build();
//
//        //订单状态变更
//        orderStateMachine.changeStatus(orders.getUserId(), orders.getId().toString(), OrderStatusChangeEventEnum.EVALUATE, orderSnapshotDTO);
    }

    /**
     * 订单取消
     *
     * @param orderCancelDTO
     */
    @Override
    public void cancel(OrderCancelDTO orderCancelDTO) {
        //查询订单信息
        Orders orders = getById(orderCancelDTO.getId());
        BeanUtils.copyProperties(orders, orderCancelDTO);
        if (ObjectUtil.isNull(orders)) {
            throw new DbRuntimeException("找不到要取消的订单,订单号：{}", orderCancelDTO.getId());
        }
        //订单状态
        Integer ordersStatus = orders.getOrdersStatus();

        if (ObjectUtils.equals(OrderStatusEnum.NO_PAY.getStatus(), ordersStatus)) { //订单状态为待支付
            if (orders.getDiscountAmount() != null) {
                owner.cancelByNoPayWithCoupon(orderCancelDTO);
            } else {
                owner.cancelByNoPay(orderCancelDTO);
            }

        } else if (ObjectUtils.equals(OrderStatusEnum.DISPATCHING.getStatus(), ordersStatus)) { //订单状态为待服务
            if (orders.getDiscountAmount() != null) {
                owner.cancelByDispatchingWithCoupon(orderCancelDTO);
            } else {
                owner.cancelByDispatching(orderCancelDTO);
            }
            //新启动一个线程请求退款
            ordersHandler.requestRefundNewThread(orders.getId());
        } else {
            throw new CommonException("当前订单状态不支持取消");
        }
    }

    /**
     * 派单中状态取消订单（有优惠券）
     *
     * @param orderCancelDTO
     */
    @GlobalTransactional
    public void cancelByDispatchingWithCoupon(OrderCancelDTO orderCancelDTO) {
        CouponUseBackReqDTO couponUseBackReqDTO = new CouponUseBackReqDTO();
        couponUseBackReqDTO.setOrdersId(orderCancelDTO.getId());
        couponUseBackReqDTO.setUserId(orderCancelDTO.getUserId());
        couponApi.useBack(couponUseBackReqDTO);
        cancelByDispatching(orderCancelDTO);
    }


    /**
     * 未支付状态取消订单（有优惠券）
     *
     * @param orderCancelDTO
     */
    @GlobalTransactional
    public void cancelByNoPayWithCoupon(OrderCancelDTO orderCancelDTO) {
        CouponUseBackReqDTO couponUseBackReqDTO = new CouponUseBackReqDTO();
        couponUseBackReqDTO.setOrdersId(orderCancelDTO.getId());
        couponUseBackReqDTO.setUserId(orderCancelDTO.getUserId());
        couponApi.useBack(couponUseBackReqDTO);
        cancelByNoPay(orderCancelDTO);
    }


    //派单中状态取消订单
    @Transactional(rollbackFor = Exception.class)
    public void cancelByDispatching(OrderCancelDTO orderCancelDTO) {
        //保存订单取消记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);
        //将订单状态更新为已关闭
//        OrderUpdateStatusDTO updateStatusDTO = OrderUpdateStatusDTO.builder()
//                .id(orderCancelDTO.getId())
//                .originStatus(OrderStatusEnum.DISPATCHING.getStatus())
//                .targetStatus(OrderStatusEnum.CLOSED.getStatus())
//                .refundStatus(OrderRefundStatusEnum.REFUNDING.getStatus())
//                .build();
//
//        Integer result = ordersCommonService.updateStatus(updateStatusDTO);
//        if (result <= 0){
//            throw new DbRuntimeException("订单取消失败");
//        }
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .cancellerId(orderCancelDTO.getCurrentUserId())
                .cancelTime(LocalDateTime.now())
                .cancelReason(orderCancelDTO.getCancelReason())
                .build();
        orderStateMachine.changeStatus(orderCancelDTO.getUserId(), orderCancelDTO.getId().toString(), OrderStatusChangeEventEnum.CLOSE_DISPATCHING_ORDER, orderSnapshotDTO);

        //保存退款记录
        OrdersRefund ordersRefund = new OrdersRefund();
        ordersRefund.setId(orderCancelDTO.getId());
        ordersRefund.setTradingOrderNo(orderCancelDTO.getTradingOrderNo());
        ordersRefund.setRealPayAmount(orderCancelDTO.getRealPayAmount());
        ordersRefundService.save(ordersRefund);
    }

    //未支付状态取消订单
    @Transactional(rollbackFor = Exception.class)
    public void cancelByNoPay(OrderCancelDTO orderCancelDTO) {
        //保存订单取消记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);
        //将订单状态更新为取消订单
        //调用状态机的方法
        /*        //更新订单状态为关闭订单
        OrderUpdateStatusDTO orderUpdateStatusDTO = OrderUpdateStatusDTO.builder().id(orderCancelDTO.getId())
                .originStatus(OrderStatusEnum.DISPATCHING.getStatus())
                .targetStatus(OrderStatusEnum.CLOSED.getStatus())
                .refundStatus(OrderRefundStatusEnum.REFUNDING.getStatus())//退款状态为退款中
                .build();
        int result = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (result <= 0) {
            throw new DbRuntimeException("待服务订单关闭事件处理失败");
        }*/
        //使用状态机处理订单取消
        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
                .cancellerId(orderCancelDTO.getCurrentUserId())
                .cancelTime(LocalDateTime.now())
                .cancelReason(orderCancelDTO.getCancelReason())
                .build();
        orderStateMachine.changeStatus(orderCancelDTO.getUserId(), orderCancelDTO.getId().toString(), OrderStatusChangeEventEnum.CANCEL, orderSnapshotDTO);

    }

}
