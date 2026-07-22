package com.jzo2o.orders.manager.service.client;


import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;


@Component
@Slf4j
public class CustomerClient {

    @Resource
    private AddressBookApi addressBookApi;

    @SentinelResource(value = "getAddressBookDetail", blockHandler = "detailBlockHandler", fallback = "detailFallback")
    public AddressBookResDTO getDetail(Long id){
        log.error("根据id查询地址簿,id:{}",id);
        try {
            AddressBookResDTO detail = addressBookApi.detail(id);
            return detail;
        } catch (Exception e) {
            log.error("查询地址簿远程调用失败,id:{},error:", id, e);
            return null;
        }
    }

    public AddressBookResDTO detailFallback(Long id,Throwable throwable){
        log.error("非限流,熔断等导致的异常执行的降级方法,id:{},throwable:{}",id,throwable);
        return null;
    }

    public AddressBookResDTO detailBlockHandler(Long id, BlockException blockException){
        log.error("限流,熔断执行的降级方法,id:{},throwable:{}",id,blockException);
        return null;
    }
}