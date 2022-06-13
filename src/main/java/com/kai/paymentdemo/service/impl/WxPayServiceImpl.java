package com.kai.paymentdemo.service.impl;

import com.github.wxpay.sdk.WXPayUtil;
import com.google.gson.Gson;
import com.kai.paymentdemo.config.WxPayConfig;
import com.kai.paymentdemo.entity.OrderInfo;
import com.kai.paymentdemo.entity.RefundInfo;
import com.kai.paymentdemo.enums.OrderStatus;
import com.kai.paymentdemo.enums.wxpay.WxApiType;
import com.kai.paymentdemo.enums.wxpay.WxNotifyType;
import com.kai.paymentdemo.enums.wxpay.WxRefundStatus;
import com.kai.paymentdemo.enums.wxpay.WxTradeState;
import com.kai.paymentdemo.service.OrderInfoService;
import com.kai.paymentdemo.service.PaymentInfoService;
import com.kai.paymentdemo.service.RefundInfoService;
import com.kai.paymentdemo.service.WxPayService;
import com.kai.paymentdemo.util.HttpClientUtils;
import com.wechat.pay.contrib.apache.httpclient.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class WxPayServiceImpl implements WxPayService {

    @Resource
    private WxPayConfig wxPayConfig;

    @Resource
    private CloseableHttpClient wxPayClient;

    @Resource
    private OrderInfoService orderInfoService;

    @Resource
    private PaymentInfoService paymentInfoService;

    @Resource
    private RefundInfoService refundsInfoService;

    @Resource
    private CloseableHttpClient wxPayNoSignClient; //无需应答签名


    private final ReentrantLock lock = new ReentrantLock();


    /**
     * Native支付接口
     *
     * <p>
     * 访问 微信 提供 的Native支付接口 获得 返回的支付二维码地址code_url
     * 访问微信的接口 除了 封装请求的url 还有 微信要求 携带的请求参数：productId 产品id
     *
     * @return code_url 支付二维码url
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public Map<String, Object> nativePay(Long productId) throws Exception {

        //一、生成订单
        log.info("生成订单");
        OrderInfo orderInfo = orderInfoService.createOrderByProductId(productId);
        //尝试获取codeURL
        String codeUrl = orderInfo.getCodeUrl();


        //1、如果有codeUrl：表示订单已存在 返回codeUrl、orderNo
        if (orderInfo != null && !StringUtils.isEmpty(codeUrl)) {
            log.info("订单已存在，二维码已保存");
            //返回二维码
            Map<String, Object> map = new HashMap<>();
            map.put("codeUrl", codeUrl);
            map.put("orderNo", orderInfo.getOrderNo());
            return map;
        }

        //2、没有codeUrl：再调用统一下单API 创建codeUrl

        //二、调用微信的返回支付二维码的API接口： 携带封装好的body参数
        log.info("调用统一下单API");

        /*调用统一下单API
        商户Native支付下单接口，微信后台系统返回链接参数code url，
        商户后台系统将code url值生成二维码图 片，角户使用微信客户端扫码后发起支付。
         */


        //1、将发送需要携带的请求body参数封装成Map集合
        // 商户号、商户私钥文件、 APIv3密钥、APPID、 微信服务器地址、接收结果通知地址、 APIv2密钥
        Gson gson = new Gson();
        Map paramsMap = new HashMap();
        paramsMap.put("appid", wxPayConfig.getAppid());
        paramsMap.put("mchid", wxPayConfig.getMchId());
        paramsMap.put("description", orderInfo.getTitle());
        paramsMap.put("out_trade_no", orderInfo.getOrderNo());
        paramsMap.put("notify_url", wxPayConfig.getNotifyDomain().concat(WxNotifyType.NATIVE_NOTIFY.getType()));
        Map amountMap = new HashMap();
        amountMap.put("total", orderInfo.getTotalFee());
        amountMap.put("currency", "CNY");

        paramsMap.put("amount", amountMap);

        //2、将Map集合的参数转换成json字符串
        String jsonParams = gson.toJson(paramsMap);
        log.info("请求参数 ===> {}" + jsonParams);

        StringEntity entity = new StringEntity(jsonParams, "utf-8");
        entity.setContentType("application/json");

        String url = wxPayConfig.getDomain().concat(WxApiType.NATIVE_PAY.getType());
        //3、获取到请求对象 将封装好的json数据传入
        HttpPost httpPost = new HttpPost(url);
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");

        //4、对请求进行签名并执行请求：签名加密 签名解密
        CloseableHttpResponse response = wxPayClient.execute(httpPost);

        try {
            String bodyAsString = EntityUtils.toString(response.getEntity());//响应体
            int statusCode = response.getStatusLine().getStatusCode();//响应状态码
            if (statusCode == 200) { //处理成功
                log.info("成功, 返回结果 = " + bodyAsString);
            } else if (statusCode == 204) { //处理成功，无返回Body
                log.info("成功");
            } else {
                log.info("Native下单失败,响应码 = " + statusCode + ",返回结果 = " + bodyAsString);
                throw new IOException("request failed");
            }

            //响应结果：封装了code_url
            Map<String, String> resultMap = gson.fromJson(bodyAsString, HashMap.class);
            //二维码链接
            codeUrl = resultMap.get("code_url");

            //订单号
            String orderNo = orderInfo.getOrderNo();
            //保存二维码（二维码链接，订单号）
            orderInfoService.saveCodeUrl(orderNo, codeUrl);

            //返回二维码
            Map<String, Object> map = new HashMap<>();
            map.put("codeUrl", codeUrl);
            map.put("orderNo", orderInfo.getOrderNo());

            return map;

        } finally {
            response.close();
        }
    }

    /**
     * 对 微信 返回的支付结果通知
     * 进行处理：此前已经验证签名成功
     * 此方向  进行：解密数据 、更新订单状态(未支付 改为 已支付)、记录支付日志
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void processOrder(Map<String, Object> bodyMap) throws GeneralSecurityException {
        log.info("处理订单");

        //解密报文
        String plainText = decryptFromResource(bodyMap);

        //将明文转换成map
        Gson gson = new Gson();
        HashMap plainTextMap = gson.fromJson(plainText, HashMap.class);
        String orderNo = (String) plainTextMap.get("out_trade_no");


        /*在对业务数据进行状态检查和处理之前，
        要采用数据锁进行并发控制，
        以避免函数重入造成的数据混乱*/
        //尝试获取锁：
        // 成功获取则立即返回true，获取失败则立即返回false。不必一直等待锁的释放
        if (lock.tryLock()) {
            try {

                //查看订单信息表中的字段order_status状态
                String orderStatus = orderInfoService.getOrderStatus(orderNo);
                if (!OrderStatus.NOTPAY.getType().equals(orderStatus)) {
                    return;
                }

                //未支付再开始处理订单

                //延迟 返回给 微信 的响应 ：模拟通知并发
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                //1、更新订单状态
                orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.SUCCESS);

                //2、记录支付日志
                paymentInfoService.createPaymentInfo(plainText);
            } finally {
                //要主动释放锁
                lock.unlock();
            }
        }
    }

    /**
     * 用户主动取消订单接口
     *
     * @param orderNo
     */
    @Override
    public void cancelOrder(String orderNo) throws Exception {

        //调用 ：微信提供的关闭支付的接口
        this.closeOrder(orderNo);

        //更新商户端的订单状态
        orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.CANCEL);
    }

    /**
     * 微信的查询订单接口
     * <p>
     * 商户 向 微信 发起支付请求
     * 微信 没有返回 支付结果的通知
     * 商户 向 微信 发起查询订单请求
     * 访问 微信 提供 的查询订单接口  携带的请求参数：orderNo 订单id
     */
    @Override
    public String queryOrder(String orderNo) throws Exception {

        log.info("查单接口调用 ===> {}", orderNo);

        //拼接
        String url = String.format(WxApiType.ORDER_QUERY_BY_NO.getType(), orderNo);
        url = wxPayConfig.getDomain().concat(url).concat("?mchid=").concat(wxPayConfig.getMchId());

        //创建 请求 微信提供的查询订单接口 的http对象
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Accept", "application/json");

        //完成签名并执行请求
        CloseableHttpResponse response = wxPayClient.execute(httpGet);

        try {
            String bodyAsString = EntityUtils.toString(response.getEntity());//响应体
            int statusCode = response.getStatusLine().getStatusCode();//响应状态码
            if (statusCode == 200) { //处理成功
                log.info("成功, 返回结果 = " + bodyAsString);
            } else if (statusCode == 204) { //处理成功，无返回Body
                log.info("成功");
            } else {
                log.info("查单接口调用,响应码 = " + statusCode + ",返回结果 = " + bodyAsString);
                throw new IOException("request failed");
            }

            return bodyAsString;

        } finally {
            response.close();
        }

    }

    /**
     * 核实订单状态接口
     * <p>
     * 根据订单号  查询   微信的查询订单接口，核实订单状态
     * 如果订单已支付，则更新商户端订单状态，并记录支付日志
     * 如果订单未支付，则调用关单接口关闭订单，并更新商户端订单状态
     *
     * @param orderNo
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void checkOrderStatus(String orderNo) throws Exception {

        log.warn("根据订单号核实订单状态 ===> {}", orderNo);

        //调用微信支付查单接口 获取到返回的数据 map集合
        String result = this.queryOrder(orderNo);

        //转成json数据格式
        Gson gson = new Gson();
        Map<String, String> resultMap = gson.fromJson(result, HashMap.class);

        //tradeState：微信查询 返回的 订单状态
        String tradeState = resultMap.get("trade_state");

        //判断订单状态
        if (WxTradeState.SUCCESS.getType().equals(tradeState)) {
            log.warn("核实订单已支付 ===> {}", orderNo);

            //如果确认订单已支付则更新本地订单状态为：已支付
            orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.SUCCESS);
            //记录支付日志
            paymentInfoService.createPaymentInfo(result);
        }

        if (WxTradeState.NOTPAY.getType().equals(tradeState)) {
            log.warn("核实订单未支付 ===> {}", orderNo);

            //如果订单未支付，则调用关单接口
            this.closeOrder(orderNo);

            //更新本地订单状态
            orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.CLOSED);
        }

    }

    /**
     * 退款接口
     *
     * @param orderNo
     * @param reason
     * @throws IOException
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void refund(String orderNo, String reason) throws Exception {

        log.info("创建退款单记录");
        //根据订单编号创建退款单
        RefundInfo refundsInfo = refundsInfoService.createRefundByOrderNo(orderNo, reason);

        log.info("调用退款API");

        //调用统一退款API
        String url = wxPayConfig.getDomain().concat(WxApiType.DOMESTIC_REFUNDS.getType());
        HttpPost httpPost = new HttpPost(url);

        // 请求body参数
        Gson gson = new Gson();
        Map paramsMap = new HashMap();
        paramsMap.put("out_trade_no", orderNo);//订单编号
        paramsMap.put("out_refund_no", refundsInfo.getRefundNo());//退款单编号
        paramsMap.put("reason", reason);//退款原因
        paramsMap.put("notify_url", wxPayConfig.getNotifyDomain().concat(WxNotifyType.REFUND_NOTIFY.getType()));//退款通知地址

        Map amountMap = new HashMap();
        amountMap.put("refund", refundsInfo.getRefund());//退款金额
        amountMap.put("total", refundsInfo.getTotalFee());//原订单金额
        amountMap.put("currency", "CNY");//退款币种
        paramsMap.put("amount", amountMap);

        //将参数转换成json字符串
        String jsonParams = gson.toJson(paramsMap);
        log.info("请求参数 ===> {}" + jsonParams);

        StringEntity entity = new StringEntity(jsonParams, "utf-8");
        entity.setContentType("application/json");//设置请求报文格式
        httpPost.setEntity(entity);//将请求报文放入请求对象
        httpPost.setHeader("Accept", "application/json");//设置响应报文格式

        //对请求进行签名  对返回的结果并完成验签 得到response对象
        CloseableHttpResponse response = wxPayClient.execute(httpPost);

        try {

            //解析响应结果
            String bodyAsString = EntityUtils.toString(response.getEntity());
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                log.info("成功, 退款返回结果 = " + bodyAsString);
            } else if (statusCode == 204) {
                log.info("成功");
            } else {
                throw new RuntimeException("退款异常, 响应码 = " + statusCode + ", 退款返回结果 = " + bodyAsString);
            }

            //更新订单状态
            orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.REFUND_PROCESSING);

            //更新退款单
            refundsInfoService.updateRefund(bodyAsString);

        } finally {
            response.close();
        }
    }


    /**
     * 查询退款接口调用
     *
     * @param refundNo
     * @return
     */
    @Override
    public String queryRefund(String refundNo) throws Exception {

        log.info("查询退款接口调用 ===> {}", refundNo);

        String url = String.format(WxApiType.DOMESTIC_REFUNDS_QUERY.getType(), refundNo);
        url = wxPayConfig.getDomain().concat(url);

        //创建远程Get 请求对象
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Accept", "application/json");

        //完成签名并执行请求
        CloseableHttpResponse response = wxPayClient.execute(httpGet);

        try {
            String bodyAsString = EntityUtils.toString(response.getEntity());
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                log.info("成功, 查询退款返回结果 = " + bodyAsString);
            } else if (statusCode == 204) {
                log.info("成功");
            } else {
                throw new RuntimeException("查询退款异常, 响应码 = " + statusCode + ", 查询退款返回结果 = " + bodyAsString);
            }

            return bodyAsString;

        } finally {
            response.close();
        }
    }

    /**
     * 根据退款单号核实退款单状态
     *
     * @param refundNo
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void checkRefundStatus(String refundNo) throws Exception {

        log.warn("根据退款单号核实退款单状态 ===> {}", refundNo);

        //调用查询退款单接口
        String result = this.queryRefund(refundNo);

        //组装json请求体字符串
        Gson gson = new Gson();
        Map<String, String> resultMap = gson.fromJson(result, HashMap.class);

        //获取微信支付端退款状态
        String status = resultMap.get("status");

        String orderNo = resultMap.get("out_trade_no");

        if (WxRefundStatus.SUCCESS.getType().equals(status)) {

            log.warn("核实订单已退款成功 ===> {}", refundNo);

            //如果确认退款成功，则更新订单状态
            orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.REFUND_SUCCESS);

            //更新退款单
            refundsInfoService.updateRefund(result);
        }

        if (WxRefundStatus.ABNORMAL.getType().equals(status)) {

            log.warn("核实订单退款异常  ===> {}", refundNo);

            //如果确认未退款成功，则更新订单状态
            orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.REFUND_ABNORMAL);

            //更新退款单
            refundsInfoService.updateRefund(result);
        }
    }

    /**
     * 处理退款单
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void processRefund(Map<String, Object> bodyMap) throws Exception {

        log.info("退款单");

        //解密报文
        String plainText = decryptFromResource(bodyMap);

        //将明文转换成map
        Gson gson = new Gson();
        HashMap plainTextMap = gson.fromJson(plainText, HashMap.class);
        String orderNo = (String) plainTextMap.get("out_trade_no");

        /*在对业务数据进行状态检查和处理之前，
        要采用数据锁进行并发控制，
        以避免函数重入造成的数据混乱*/
        //尝试获取锁：
        // 成功获取则立即返回true，获取失败则立即返回false。不必一直等待锁的释放
        if (lock.tryLock()) {
            try {

                String orderStatus = orderInfoService.getOrderStatus(orderNo);
                if (!OrderStatus.REFUND_PROCESSING.getType().equals(orderStatus)) {
                    return;
                }

                //更新订单状态
                orderInfoService.updateStatusByOrderNo(orderNo, OrderStatus.REFUND_SUCCESS);

                //更新退款单
                refundsInfoService.updateRefund(plainText);

            } finally {
                //要主动释放锁
                lock.unlock();
            }
        }
    }

    /**
     * 申请账单
     *
     * @param billDate
     * @param type
     * @return
     * @throws Exception
     */
    @Override
    public String queryBill(String billDate, String type) throws Exception {
        log.warn("申请账单接口调用 {}", billDate);

        String url = "";
        if ("tradebill".equals(type)) {
            url = WxApiType.TRADE_BILLS.getType();
        } else if ("fundflowbill".equals(type)) {
            url = WxApiType.FUND_FLOW_BILLS.getType();
        } else {
            throw new RuntimeException("不支持的账单类型");
        }

        url = wxPayConfig.getDomain().concat(url).concat("?bill_date=").concat(billDate);

        //创建远程Get 请求对象
        HttpGet httpGet = new HttpGet(url);
        httpGet.addHeader("Accept", "application/json");

        //使用wxPayClient发送请求得到响应
        CloseableHttpResponse response = wxPayClient.execute(httpGet);

        try {

            String bodyAsString = EntityUtils.toString(response.getEntity());

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                log.info("成功, 申请账单返回结果 = " + bodyAsString);
            } else if (statusCode == 204) {
                log.info("成功");
            } else {
                throw new RuntimeException("申请账单异常, 响应码 = " + statusCode + ", 申请账单返回结果 = " + bodyAsString);
            }

            //获取账单下载地址
            Gson gson = new Gson();
            Map<String, String> resultMap = gson.fromJson(bodyAsString, HashMap.class);
            return resultMap.get("download_url");

        } finally {
            response.close();
        }
    }

    /**
     * 下载账单
     *
     * @param billDate
     * @param type
     * @return
     * @throws Exception
     */
    @Override
    public String downloadBill(String billDate, String type) throws Exception {
        log.warn("下载账单接口调用 {}, {}", billDate, type);

        //获取账单url地址
        String downloadUrl = this.queryBill(billDate, type);
        //创建远程Get 请求对象
        HttpGet httpGet = new HttpGet(downloadUrl);
        httpGet.addHeader("Accept", "application/json");

        //使用wxPayClient发送请求得到响应
        CloseableHttpResponse response = wxPayNoSignClient.execute(httpGet);

        try {

            String bodyAsString = EntityUtils.toString(response.getEntity());

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 200) {
                log.info("成功, 下载账单返回结果 = " + bodyAsString);
            } else if (statusCode == 204) {
                log.info("成功");
            } else {
                throw new RuntimeException("下载账单异常, 响应码 = " + statusCode + ", 下载账单返回结果 = " + bodyAsString);
            }

            return bodyAsString;

        } finally {
            response.close();
        }
    }

    @Override
    public Map<String, Object> nativePayV2(Long productId, String remoteAddr) throws Exception {

        log.info("生成订单");

        //生成订单
        OrderInfo orderInfo = orderInfoService.createOrderByProductId(productId);
        String codeUrl = orderInfo.getCodeUrl();
        if (orderInfo != null && !StringUtils.isEmpty(codeUrl)) {
            log.info("订单已存在，二维码已保存");
            //返回二维码
            Map<String, Object> map = new HashMap<>();
            map.put("codeUrl", codeUrl);
            map.put("orderNo", orderInfo.getOrderNo());
            return map;
        }

        log.info("调用统一下单API");

        HttpClientUtils client = new HttpClientUtils(wxPayConfig.getDomain().concat(WxApiType.NATIVE_PAY_V2.getType()));

        //组装接口参数
        Map<String, String> params = new HashMap<>();
        params.put("appid", wxPayConfig.getAppid());//关联的公众号的appid
        params.put("mch_id", wxPayConfig.getMchId());//商户号
        params.put("nonce_str", WXPayUtil.generateNonceStr());//生成随机字符串
        params.put("body", orderInfo.getTitle());
        params.put("out_trade_no", orderInfo.getOrderNo());

        //注意，这里必须使用字符串类型的参数（总金额：分）
        String totalFee = orderInfo.getTotalFee() + "";
        params.put("total_fee", totalFee);

        params.put("spbill_create_ip", remoteAddr);
        params.put("notify_url", wxPayConfig.getNotifyDomain().concat(WxNotifyType.NATIVE_NOTIFY_V2.getType()));
        params.put("trade_type", "NATIVE");

        //将参数转换成xml字符串格式：生成带有签名的xml格式字符串
        String xmlParams = WXPayUtil.generateSignedXml(params, wxPayConfig.getPartnerKey());
        log.info("\n xmlParams：\n" + xmlParams);

        client.setXmlParam(xmlParams);//将参数放入请求对象的方法体
        client.setHttps(true);//使用https形式发送
        client.post();//发送请求
        String resultXml = client.getContent();//得到响应结果
        log.info("\n resultXml：\n" + resultXml);
        //将xml响应结果转成map对象
        Map<String, String> resultMap = WXPayUtil.xmlToMap(resultXml);

        //错误处理
        if ("FAIL".equals(resultMap.get("return_code")) || "FAIL".equals(resultMap.get("result_code"))) {
            log.error("微信支付统一下单错误 ===> {} ", resultXml);
            throw new RuntimeException("微信支付统一下单错误");
        }

        //二维码
        codeUrl = resultMap.get("code_url");

        //保存二维码
        String orderNo = orderInfo.getOrderNo();
        orderInfoService.saveCodeUrl(orderNo, codeUrl);

        //返回二维码
        Map<String, Object> map = new HashMap<>();
        map.put("codeUrl", codeUrl);
        map.put("orderNo", orderInfo.getOrderNo());

        return map;
    }

    /**
     * 调用 微信的关单接口
     * <p>
     * 调用 微信提供的关单接口URL：https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/fout trade no)/close
     * 需要 创建   HttpPost httpPost = new HttpPost(url);
     * url ：远程微信服务器的ip地址==本地的localhost + 接口的访问路径URL
     * 访问微信的接口 除了 封装请求的url 还有 微信要求 携带的请求参数：
     * 1、直连商户号：mchid
     * 2、out_trade_no：商户订单号
     */
    private void closeOrder(String orderNo) throws Exception {

        log.info("关单接口的调用，订单号 ===> {}", orderNo);

        //创建远程请求对象
        //String.format：替换占位符%s->传入关闭订单支付的订单id【"/v3/pay/transactions/out-trade-no/%s/close"】
        String url = String.format(WxApiType.CLOSE_ORDER_BY_NO.getType(), orderNo);
        url = wxPayConfig.getDomain().concat(url);
        HttpPost httpPost = new HttpPost(url);

        //组装json请求体
        Gson gson = new Gson();
        Map<String, String> paramsMap = new HashMap<>();
        paramsMap.put("mchid", wxPayConfig.getMchId());
        String jsonParams = gson.toJson(paramsMap);
        log.info("请求参数 ===> {}", jsonParams);

        //将请求参数设置到请求对象中
        StringEntity entity = new StringEntity(jsonParams, "utf-8");
        entity.setContentType("application/json");
        httpPost.setEntity(entity);
        httpPost.setHeader("Accept", "application/json");

        //完成签名并执行请求
        CloseableHttpResponse response = wxPayClient.execute(httpPost);

        try {
            int statusCode = response.getStatusLine().getStatusCode();//响应状态码
            if (statusCode == 200) { //处理成功
                log.info("成功200");
            } else if (statusCode == 204) { //处理成功，无返回Body
                log.info("成功204");
            } else {
                log.info("Native下单失败,响应码 = " + statusCode);
                throw new IOException("request failed");
            }

        } finally {
            response.close();
        }
    }

    /**
     * 利用对称算法的密钥 进行解密 返回明文
     *
     * @param bodyMap
     * @return
     */
    private String decryptFromResource(Map<String, Object> bodyMap) throws GeneralSecurityException {

        log.info("密文解密");

        //获取返回的通知数据
        Map<String, String> resourceMap = (Map) bodyMap.get("resource");
        //获取通知的数据密文
        String ciphertext = resourceMap.get("ciphertext");
        //获取通知的随机串
        String nonce = resourceMap.get("nonce");
        //获取通知的附加数据
        String associatedData = resourceMap.get("associated_data");

        log.info("密文 ===> {}", ciphertext);
        AesUtil aesUtil = new AesUtil(wxPayConfig.getApiV3Key().getBytes(StandardCharsets.UTF_8));
        String plainText = aesUtil.decryptToString(associatedData.getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8),
                ciphertext);

        log.info("明文 ===> {}", plainText);

        return plainText;
    }


}
