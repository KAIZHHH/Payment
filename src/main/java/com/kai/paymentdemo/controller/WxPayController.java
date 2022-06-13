package com.kai.paymentdemo.controller;

import com.google.gson.Gson;
import com.kai.paymentdemo.service.WxPayService;
import com.kai.paymentdemo.util.HttpUtils;
import com.kai.paymentdemo.util.WechatPay2ValidatorForRequest;
import com.kai.paymentdemo.vo.R;
import com.wechat.pay.contrib.apache.httpclient.auth.Verifier;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@CrossOrigin //跨域
@RestController
@RequestMapping("/api/wx-pay")
@Api(tags = "网站微信支付APIv3")
@Slf4j
public class WxPayController {

    @Resource
    private WxPayService wxPayService;

    @Resource
    private Verifier verifier;

    /**
     * Native支付接口
     * <p>
     * 前端访问该接口 向 微信 发起申请支付请求
     * 携带的请求参数：productId 订单id
     */
    @ApiOperation("调用统一下单API，生成支付二维码")
    @PostMapping("/native/{productId}")
    public R nativePay(@PathVariable Long productId) throws Exception {

        log.info("发起支付请求 v3");

        //返回Map【支付二维码url(提供扫码支付)和订单号】
        Map<String, Object> map = wxPayService.nativePay(productId);

        return R.ok().setData(map);
    }

    /**
     * 申请退款接口
     * <p>
     * 前端访问该接口 向 微信 发起申请退款请求
     * 携带的请求参数：refundNo 退款订单id
     */
    @ApiOperation("申请退款")
    @PostMapping("/refunds/{orderNo}/{reason}")
    public R refunds(@PathVariable String orderNo, @PathVariable String reason) throws Exception {

        log.info("申请退款");
        wxPayService.refund(orderNo, reason);
        return R.ok();
    }

    /**
     * 支付结果通知接口
     * <p>
     * 微信访问该接口
     * 进行 验证签名、处理退款单方法、解密、修改订单状态、增加支付日志的记录
     */

    @ApiOperation("支付通知")
    @PostMapping("/native/notify")
    public String nativeNotify(HttpServletRequest request, HttpServletResponse response) {

        Gson gson = new Gson();
        Map<String, String> map = new HashMap<>();//应答对象

        try {

            //处理通知参数
            String body = HttpUtils.readData(request);
            Map<String, Object> bodyMap = gson.fromJson(body, HashMap.class);
            //获取支付通知的唯一id
            String requestId = (String) bodyMap.get("id");
            log.info("支付通知的id ===> {}", requestId);
            //log.info("支付通知的完整数据 ===> {}", body);
            //int a = 9 / 0;

            //验证签名【利用微信支付系统的公钥】
            WechatPay2ValidatorForRequest wechatPay2ValidatorForRequest = new WechatPay2ValidatorForRequest(verifier, requestId, body);

            //验证失败
            if (!wechatPay2ValidatorForRequest.validate(request)) {

                log.error("通知验签失败");
                //失败应答
                response.setStatus(500);
                map.put("code", "ERROR");
                map.put("message", "通知验签失败");
                return gson.toJson(map);
            }

            log.info("通知验签成功");

            //验证签名成功 进行 解密数据()
            //处理订单(密钥解密)
            wxPayService.processOrder(bodyMap);

            //应答超时
            //模拟接收微信端的重复通知
            TimeUnit.SECONDS.sleep(5);

            //成功应答
            response.setStatus(200);
            map.put("code", "SUCCESS");
            map.put("message", "成功");
            return gson.toJson(map);

        } catch (Exception e) {
            e.printStackTrace();
            //失败应答
            response.setStatus(500);
            map.put("code", "ERROR");
            map.put("message", "失败");
            return gson.toJson(map);
        }

    }

    /**
     * 退款结果通知接口
     * <p>
     * 微信访问该接口
     * 进行 验证签名、处理退款单方法、解密、修改订单状态、补充 退款记录数据【t_refund_info】
     */
    @ApiOperation("退款结果通知")
    @PostMapping("/refunds/notify")
    public String refundsNotify(HttpServletRequest request, HttpServletResponse response) {

        log.info("退款通知执行");
        Gson gson = new Gson();
        Map<String, String> map = new HashMap<>();//应答对象

        try {
            //处理通知参数
            String body = HttpUtils.readData(request);
            Map<String, Object> bodyMap = gson.fromJson(body, HashMap.class);
            String requestId = (String) bodyMap.get("id");
            log.info("支付通知的id ===> {}", requestId);

            //签名的验证
            WechatPay2ValidatorForRequest wechatPay2ValidatorForRequest
                    = new WechatPay2ValidatorForRequest(verifier, requestId, body);
            if (!wechatPay2ValidatorForRequest.validate(request)) {

                log.error("通知验签失败");
                //失败应答
                response.setStatus(500);
                map.put("code", "ERROR");
                map.put("message", "通知验签失败");
                return gson.toJson(map);
            }
            log.info("通知验签成功");

            //处理退款单
            wxPayService.processRefund(bodyMap);

            //成功应答
            response.setStatus(200);
            map.put("code", "SUCCESS");
            map.put("message", "成功");
            return gson.toJson(map);

        } catch (Exception e) {
            e.printStackTrace();
            //失败应答
            response.setStatus(500);
            map.put("code", "ERROR");
            map.put("message", "失败");
            return gson.toJson(map);
        }
    }


    /**
     * 查询订单接口
     * <p>
     * 系统主动 向 微信 发起 查询订单请求
     * 携带的请求参数：orderNo 订单id
     */
    @ApiOperation("查询订单：测试订单状态用")
    @GetMapping("/query/{orderNo}")
    public R queryOrder(@PathVariable String orderNo) throws Exception {

        log.info("查询订单");

        String result = wxPayService.queryOrder(orderNo);
        return R.ok().setMessage("查询成功").data("result", result);

    }


    /**
     * 查询退款接口
     * <p>
     * 系统主动访问该接口 向 微信 发起查询退款请求
     * 携带的请求参数：refundNo 退款订单id
     */
    @ApiOperation("查询退款：测试用")
    @GetMapping("/query-refund/{refundNo}")
    public R queryRefund(@PathVariable String refundNo) throws Exception {

        log.info("查询退款");

        String result = wxPayService.queryRefund(refundNo);
        return R.ok().setMessage("查询成功").data("result", result);
    }


    /**
     * 查询账单接口
     * <p>
     * 前端访问该接口 向微信 发起查询账单请求 需要携带：
     * billDate  交易账单日期
     * type  交易账单类型
     */
    @ApiOperation("获取账单url：测试用")
    @GetMapping("/querybill/{billDate}/{type}")
    public R queryTradeBill(
            @PathVariable String billDate,
            @PathVariable String type) throws Exception {

        log.info("获取账单url");

        String downloadUrl = wxPayService.queryBill(billDate, type);
        return R.ok().setMessage("获取账单url成功").data("downloadUrl", downloadUrl);
    }

    /**
     * 下载账单接口
     * <p>
     * 前端访问该接口 向微信 发起查下载账单请求 需要携带：
     * billDate  交易账单日期
     * type  交易账单类型
     */
    @ApiOperation("下载账单")
    @GetMapping("/downloadbill/{billDate}/{type}")
    public R downloadBill(
            @PathVariable String billDate,
            @PathVariable String type) throws Exception {

        log.info("下载账单");
        String result = wxPayService.downloadBill(billDate, type);

        return R.ok().data("result", result);
    }

    /**
     * 取消订单接口
     * <p>
     * 创建订单生成二维码未支付 可以再订单信息中 将该订单进行手动取消
     * 前端访问该接口 向 微信 发起 取消订单请求
     * 携带参数： orderNo 订单id
     */
    @ApiOperation("用户取消订单")
    @PostMapping("/cancel/{orderNo}")
    public R cancel(@PathVariable String orderNo) throws Exception {

        log.info("取消订单");

        wxPayService.cancelOrder(orderNo);
        return R.ok().setMessage("订单已取消");
    }
}
