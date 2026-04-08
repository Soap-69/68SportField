package com.cardshowcase.service;

import com.cardshowcase.model.dto.BannerDTO;
import com.cardshowcase.model.entity.Banner;
import com.cardshowcase.repository.BannerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BannerService {

    private final BannerRepository bannerRepository;
    private final FileUploadService fileUploadService;

    @Transactional(readOnly = true)
    public List<Banner> getAllBanners() {
        return bannerRepository.findAllByOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public List<Banner> getActiveBanners() {
        return bannerRepository.findByIsActiveTrueOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public Banner getBannerById(Long id) {
        return bannerRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Banner not found: " + id));
    }

    @Transactional
    public Banner createBanner(BannerDTO dto, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Banner image is required.");
        }
        String imageUrl = fileUploadService.uploadFile(image, "banners");
        Banner banner = Banner.builder()
            .title(dto.getTitle())
            .imageUrl(imageUrl)
            .linkUrl(dto.getLinkUrl())
            .sortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0)
            .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
            .build();
        return bannerRepository.save(banner);
    }

    @Transactional
    public Banner updateBanner(Long id, BannerDTO dto, MultipartFile newImage) {
        Banner banner = getBannerById(id);
        if (newImage != null && !newImage.isEmpty()) {
            if (banner.getImageUrl() != null) {
                fileUploadService.deleteFile(banner.getImageUrl());
            }
            banner.setImageUrl(fileUploadService.uploadFile(newImage, "banners"));
        }
        banner.setTitle(dto.getTitle());
        banner.setLinkUrl(dto.getLinkUrl());
        banner.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
        banner.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : false);
        return bannerRepository.save(banner);
    }

    @Transactional
    public void deleteBanner(Long id) {
        Banner banner = getBannerById(id);
        if (banner.getImageUrl() != null) {
            fileUploadService.deleteFile(banner.getImageUrl());
        }
        bannerRepository.delete(banner);
    }

    @Transactional
    public void toggleActive(Long id) {
        Banner banner = getBannerById(id);
        banner.setIsActive(!Boolean.TRUE.equals(banner.getIsActive()));
        bannerRepository.save(banner);
    }

    @Transactional
    public void updateSortOrder(Long id, Integer sortOrder) {
        Banner banner = getBannerById(id);
        banner.setSortOrder(sortOrder != null ? sortOrder : 0);
        bannerRepository.save(banner);
    }

    public BannerDTO toDTO(Long id) {
        Banner banner = getBannerById(id);
        BannerDTO dto = new BannerDTO();
        dto.setId(banner.getId());
        dto.setTitle(banner.getTitle());
        dto.setLinkUrl(banner.getLinkUrl());
        dto.setSortOrder(banner.getSortOrder());
        dto.setIsActive(banner.getIsActive());
        return dto;
    }
}
