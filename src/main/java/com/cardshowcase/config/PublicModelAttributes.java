package com.cardshowcase.config;

import com.cardshowcase.controller.CategoryController;
import com.cardshowcase.controller.HomeController;
import com.cardshowcase.controller.InquiryController;
import com.cardshowcase.controller.ProductController;
import com.cardshowcase.controller.ProductListController;
import com.cardshowcase.controller.SearchController;
import com.cardshowcase.model.dto.CategoryTreeNode;
import com.cardshowcase.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * Injects category tree data into every public (non-admin) controller's model.
 * Add new public controllers to {@code assignableTypes} as they are created.
 */
@ControllerAdvice(assignableTypes = {HomeController.class, CategoryController.class, ProductController.class, ProductListController.class, SearchController.class, InquiryController.class})
@RequiredArgsConstructor
public class PublicModelAttributes {

    private final CategoryService categoryService;

    @ModelAttribute("categoryTree")
    public List<CategoryTreeNode> categoryTree() {
        return categoryService.getActiveCategoryTree();
    }
}
