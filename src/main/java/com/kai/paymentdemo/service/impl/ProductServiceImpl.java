package com.kai.paymentdemo.service.impl;

import com.kai.paymentdemo.entity.Product;
import com.kai.paymentdemo.mapper.ProductMapper;
import com.kai.paymentdemo.service.ProductService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

}
