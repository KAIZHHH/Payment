package com.kai.paymentdemo.controller;

import com.kai.paymentdemo.entity.OrderInfo;
import com.kai.paymentdemo.enums.OrderStatus;
import com.kai.paymentdemo.service.OrderInfoService;
import com.kai.paymentdemo.vo.R;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

@CrossOrigin //开放前端的跨域访问
@Api(tags = "商品订单管理")
@RestController
@RequestMapping("/api/order-info")
public class OrderInfoController {

    @Resource
    private OrderInfoService orderInfoService;

    /**
     * 查询订单列表
     */
    @ApiOperation("订单列表")
    @GetMapping("/list")
    public R list() {

        List<OrderInfo> list = orderInfoService.listOrderByCreateTimeDesc();
        return R.ok().data("list", list);
    }

    /**
     * 查询本地订单状态
     * 用户已经扫码支付成功 前端定时 访问该接口 进行查询是否支付成功 后 返回响应
     */
    @ApiOperation("查询本地订单状态")
    @GetMapping("/query-order-status/{orderNo}")
    public R queryOrderStatus(@PathVariable String orderNo) {

        String orderStatus = orderInfoService.getOrderStatus(orderNo);
        if (OrderStatus.SUCCESS.getType().equals(orderStatus)) {
            return R.ok().setMessage("支付成功"); //支付成功
        }

        return R.ok().setCode(101).setMessage("支付中......");
    }


}
