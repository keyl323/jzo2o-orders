package com.jzo2o.orders.manager.service.client;


import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.market.dto.CouponApi;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.List;


@Component
@Slf4j
public class MarketClient {

    @Resource
    private CouponApi couponApi;

    @SentinelResource(value = "getAvailableByCouponApi", blockHandler = "getAvailableBlockHandler", fallback = "getAvailableFallBack")
    public List<AvailableCouponsResDTO> getAvailable(BigDecimal totalAmount){
        log.error("查询可用优惠券,订单金额:{}",totalAmount);
        List<AvailableCouponsResDTO> available = couponApi.getAvailable(totalAmount);
        return available;
    }

    public AddressBookResDTO getAvailableFallBack(BigDecimal totalAmount, Throwable throwable){
        log.error("非限流,熔断等导致的异常执行的降级方法,totalAmount:{},throwable:{}",totalAmount,throwable);
        return null;
    }

    public AddressBookResDTO getAvailableBlockHandler(BigDecimal totalAmount, BlockException blockException){
        log.error("触发限流,熔断执行的降级方法,totalAmount:{},throwable:{}",totalAmount,blockException);
        return null;
    }

}