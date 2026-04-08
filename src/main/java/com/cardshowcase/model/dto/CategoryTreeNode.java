package com.cardshowcase.model.dto;

import com.cardshowcase.model.entity.Category;
import java.util.List;

public record CategoryTreeNode(Category category, List<CategoryTreeNode> children) {}
