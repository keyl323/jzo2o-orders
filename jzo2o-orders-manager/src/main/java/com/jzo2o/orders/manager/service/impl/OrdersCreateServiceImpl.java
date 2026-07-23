package com.jzo2o.orders.manager.service.impl;

import cn.hutool.db.DbRuntimeException;
import cn.hutool.db.sql.Order;
import cn.hutool.log.dialect.commons.ApacheCommonsLog4JLog;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import com.jzo2o.api.orders.dto.request.OrderCancelReqDTO;
import com.jzo2o.api.trade.NativePayApi;
import com.jzo2o.api.trade.TradingApi;
import com.jzo2o.api.trade.dto.request.NativePayReqDTO;
import com.jzo2o.api.trade.dto.response.NativePayResDTO;
import com.jzo2o.api.trade.dto.response.TradingResDTO;
import com.jzo2o.api.trade.enums.PayChannelEnum;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.DateUtils;
import com.jzo2o.common.utils.NumberUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.mvc.utils.UserContext;
import com.jzo2o.orders.base.constants.RedisConstants;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.porperties.TradeProperties;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.client.CustomerClient;
import com.rabbitmq.client.Return;
import io.lettuce.core.dynamic.CommandCreationException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.apache.bcel.generic.ObjectType;
import org.elasticsearch.action.admin.indices.delete.TransportDeleteIndexAction;
import org.springframework.boot.autoconfigure.mongo.embedded.DownloadConfigBuilderCustomizer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.events.Event;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.jzo2o.common.constants.ErrorInfo.Code.TRADE_FAILED;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author yutsung chen
 */
@Slf4j
@Service
public class OrdersCreateServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersCreateService {


    @Resource
    private CustomerClient consumerClient;
    @Resource
    private RedisTemplate<String,Long> redisTemplate;
    @Resource
    private ServeApi serveApi;
    @Resource
    private OrdersCreateServiceImpl owner;
    @Resource
    private NativePayApi nativePayApi;
    @Resource
    private TradeProperties tradeProperties;
    @Resource
    private TradingApi tradingApi;

    /**
     * 生成订单id
     * @return
     */
    private Long generateOrderId(){
        Long id = redisTemplate.opsForValue().increment(RedisConstants.Lock.ORDERS_SHARD_KEY_ID_GENERATOR, 1);
        long orderId = DateUtils.getFormatDate(LocalDateTime.now(), "yyMMdd") * 10000000000000L + id;
        return orderId;
    }



    /**
     * 下单接口
     *
     * @param placeOrderReqDTO
     * @return
     */
    @Override
    public PlaceOrderResDTO placeOrder(PlaceOrderReqDTO placeOrderReqDTO) {
        // 1. 数据校验
        // 校验服务地址
        AddressBookResDTO detail = consumerClient.getDetail(placeOrderReqDTO.getAddressBookId());
        if (detail == null) {
            throw new BadRequestException("服务地址不存在");
        }

        // 服务
        ServeAggregationResDTO serveResDTO = serveApi.findById(placeOrderReqDTO.getServeId());
        // 服务下架不可下单
        if (serveResDTO == null || serveResDTO.getSaleStatus() != 2) {
            throw new BadRequestException("服务不可用");
        }

        // 2. 下单前数据准备
        Orders orders = new Orders();
        // id 订单id
        orders.setId(generateOrderId());
        // userId，从threadLocal获取当前登录用户的id，通过UserContextInterceptor拦截进行设置
        orders.setUserId(UserContext.currentUserId());
        // 服务id
        orders.setServeId(placeOrderReqDTO.getServeId());
        // 服务项id
        orders.setServeItemId(serveResDTO.getServeItemId());
        orders.setServeItemName(serveResDTO.getServeItemName());
        orders.setServeItemImg(serveResDTO.getServeItemImg());
        orders.setUnit(serveResDTO.getUnit());
        // 服务类型信息
        orders.setServeTypeId(serveResDTO.getServeTypeId());
        orders.setServeTypeName(serveResDTO.getServeTypeName());
        // 订单状态
        orders.setOrdersStatus(0);
        // 支付状态，暂不支持，初始化一个空状态
        orders.setPayStatus(OrderPayStatusEnum.NO_PAY.getStatus());
        // 服务时间
        orders.setServeStartTime(placeOrderReqDTO.getServeStartTime());
        // 城市编码
        orders.setCityCode(serveResDTO.getCityCode());
        // 地理位置
        orders.setLon(detail.getLon());
        orders.setLat(detail.getLat());

        String serveAddress = new StringBuffer(detail.getProvince())
                .append(detail.getCity())
                .append(detail.getCounty())
                .append(detail.getAddress())
                .toString();
        orders.setServeAddress(serveAddress);
        // 联系人
        orders.setContactsName(detail.getName());
        orders.setContactsPhone(detail.getPhone());

        // 价格
        orders.setPrice(serveResDTO.getPrice());
        // 购买数量
        orders.setPurNum(NumberUtils.null2Default(placeOrderReqDTO.getPurNum(), 1));
        // 订单总金额 价格 * 购买数量
        orders.setTotalAmount(orders.getPrice().multiply(new BigDecimal(orders.getPurNum())));

        // 优惠金额 当前默认0
        orders.setDiscountAmount(BigDecimal.ZERO);
        // 实付金额 订单总金额 - 优惠金额
        orders.setRealPayAmount(NumberUtils.sub(orders.getTotalAmount(), orders.getDiscountAmount()));
        // 排序字段, 根据服务开始时间转为毫秒时间戳+订单后5位
        long sortBy = DateUtils.toEpochMilli(orders.getServeStartTime()) + orders.getId() % 100000;
        orders.setSortBy(sortBy);
        // 保存订单
        owner.add(orders);
        return new PlaceOrderResDTO(orders.getId());
    }

    /**
     * 生成订单
     * @param orders
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Orders orders) {
        boolean save = this.save(orders);
        if (!save) {
            throw new DbRuntimeException("下单失败");
        }
    }

    /**
     * 订单支付
     *
     * @param id
     * @param ordersPayReqDTO
     * @return
     */
    @Override
    public OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO) {
        Orders orders = getById(id);
        if (ObjectUtils.isNull(orders)){
            throw new CommonException("订单不存在");
        }
        if (OrderPayStatusEnum.PAY_SUCCESS.getStatus() == orders.getPayStatus() && ObjectUtils.isNotEmpty(orders.getTradingOrderNo())){
            OrdersPayResDTO ordersPayResDTO = new OrdersPayResDTO();
            BeanUtils.copyProperties(orders, ordersPayResDTO);
            ordersPayResDTO.setProductOrderNo(orders.getId());
            return ordersPayResDTO;
        }else {
            NativePayResDTO nativePayResDTO = generateQrCode(orders,ordersPayReqDTO.getTradingChannel());
            OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(nativePayResDTO, OrdersPayResDTO.class);
            return ordersPayResDTO;
        }
    }

    /**
     * 请求支付服务查询结果
     * @param id
     * @return
     */
    @Override
    public OrdersPayResDTO getPayResultFromTradeServer(Long id) {
        Orders orders = getById(id);
        if (ObjectUtils.isNull(orders)){
            throw new CommonException(TRADE_FAILED,"订单不存在");
        }

        Integer payStatus = orders.getPayStatus();
        //如果状态是未支付才能请求支付服务拿支付结果 将未支付->已支付
        if (OrderPayStatusEnum.NO_PAY.getStatus() == payStatus && ObjectUtils.isNotEmpty(orders.getTradingOrderNo())){
            TradingResDTO tradingResDTO = tradingApi.findTradResultByTradingOrderNo(orders.getTradingOrderNo());
            if (ObjectUtils.isNotNull(tradingResDTO) && ObjectUtils.equals(tradingResDTO.getTradingState(), TradingStateEnum.YJS)){
                TradeStatusMsg msg = TradeStatusMsg.builder()
                        .productOrderNo(orders.getId())
                        .tradingChannel(tradingResDTO.getTradingChannel())
                        .statusCode(OrderPayStatusEnum.PAY_SUCCESS.getStatus())
                        .statusName(OrderPayStatusEnum.PAY_SUCCESS.name())
                        .tradingOrderNo(tradingResDTO.getTradingOrderNo())
                        .transactionId(tradingResDTO.getTransactionId())
                        .build();
                owner.paySuccess(msg);
                //构造返回数据
                OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(msg, OrdersPayResDTO.class);
                ordersPayResDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
                return ordersPayResDTO;
            }
        }
        OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(orders, OrdersPayResDTO.class);
        return ordersPayResDTO;
    }

    /**
     * 支付成功
     * @param tradeStatusMsg
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void paySuccess(TradeStatusMsg tradeStatusMsg) {
        Orders orders = getById(tradeStatusMsg.getProductOrderNo());
        if (ObjectUtils.isNull(orders)){
            throw new CommonException(TRADE_FAILED,"订单不存在");
        }

        if (ObjectUtils.notEqual(OrderPayStatusEnum.NO_PAY.getStatus(),orders.getPayStatus())){
            log.info("更新订单支付成功,当前订单:{}支付状态不是待支付状态",orders.getId());
            return;
        }

        if (ObjectUtils.isEmpty(tradeStatusMsg.getTransactionId())){
            throw new CommonException("支付成功通知第三方支付单号");
        }

        //将订单状态改为派单中和支付成功
        boolean update = lambdaUpdate().eq(Orders::getId, orders.getId())
                .set(Orders::getPayTime, LocalDateTime.now())
                .set(Orders::getTradingOrderNo, tradeStatusMsg.getTradingOrderNo())
                .set(Orders::getTradingChannel, tradeStatusMsg.getTradingChannel())
                .set(Orders::getTransactionId, tradeStatusMsg.getTransactionId())
                .set(Orders::getPayStatus, OrderPayStatusEnum.PAY_SUCCESS.getStatus())
                .set(Orders::getOrdersStatus, OrderStatusEnum.DISPATCHING.getStatus())
                .update();
        if (!update){
            log.info("更新订单:{}支付成功失败",orders.getId());
            throw new CommonException("更新订单"+orders.getId()+"支付成功失败");
        }
    }

    /**
     * 查询支付超时订单id列表
     * @param count
     * @return
     */
    @Override
    public List<Orders> queryOverTimePayOrdersListByCount(Integer count) {
        List<Orders> list = lambdaQuery().eq(Orders::getOrdersStatus, OrderStatusEnum.NO_PAY.getStatus())
                .lt(Orders::getCreateTime, LocalDateTime.now().minusMinutes(15))
                .last("limit " + count)
                .list();
        return list;
    }


    /**
     * 生成二维码
     * @param orders
     * @param tradingChannel
     * @return
     */
    private NativePayResDTO generateQrCode(Orders orders, PayChannelEnum tradingChannel) {
        Long enterpriseId = ObjectUtils.equal(PayChannelEnum.ALI_PAY, tradingChannel) ? tradeProperties.getAliEnterpriseId() : tradeProperties.getWechatEnterpriseId();
        NativePayReqDTO nativePayReqDTO = new NativePayReqDTO();
        nativePayReqDTO.setEnterpriseId(enterpriseId);
        nativePayReqDTO.setProductOrderNo(orders.getId());
        nativePayReqDTO.setProductAppId("jzo2o.orders");
        nativePayReqDTO.setTradingChannel(tradingChannel);
        nativePayReqDTO.setTradingAmount(orders.getRealPayAmount());
        nativePayReqDTO.setMemo(orders.getServeItemName());

        if (ObjectUtils.isNotEmpty(orders.getTradingChannel()) && ObjectUtils.notEqual(orders.getTradingChannel(),tradingChannel.toString())){
            nativePayReqDTO.setChangeChannel(true);
        }

        NativePayResDTO downLineTrading = nativePayApi.createDownLineTrading(nativePayReqDTO);
        if (ObjectUtils.isNotNull(downLineTrading)){
            log.info("订单:{}请求支付,生成二维码:{}",orders.getId(),downLineTrading.toString());
            boolean update = lambdaUpdate().eq(Orders::getId, downLineTrading.getProductOrderNo())
                    .set(Orders::getTradingOrderNo, downLineTrading.getTradingOrderNo())
                    .set(Orders::getTradingChannel, downLineTrading.getTradingChannel())
                    .update();
            if (!update){
                throw new CommonException("订单:"+orders.getId()+"请求支付更新交易单号失败");
            }
        }
        return downLineTrading;
    }
}
