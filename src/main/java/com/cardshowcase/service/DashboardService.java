package com.cardshowcase.service;

import com.cardshowcase.repository.BannerRepository;
import com.cardshowcase.repository.CategoryRepository;
import com.cardshowcase.repository.InquiryRepository;
import com.cardshowcase.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private final ProductRepository  productRepository;
    private final CategoryRepository categoryRepository;
    private final InquiryRepository  inquiryRepository;
    private final BannerRepository   bannerRepository;

    public long getTotalProducts()      { return productRepository.count(); }

    public long getTotalCategories()    { return categoryRepository.count(); }

    public long getNewInquiriesCount()  { return inquiryRepository.countByStatus("NEW"); }

    public long getActiveBannersCount() { return bannerRepository.countByIsActiveTrue(); }
}
