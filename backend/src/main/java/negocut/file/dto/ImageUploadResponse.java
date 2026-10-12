package negocut.file.dto;

import negocut.file.entity.ImageFile;

// API 명세서 2장 POST /api/images
public record ImageUploadResponse(Long imageId, String url, String originalName, String contentType, long fileSize) {

    public static ImageUploadResponse from(ImageFile image) {
        return new ImageUploadResponse(image.getId(), "/api/images/" + image.getId(), image.getOriginalName(),
                image.getContentType(), image.getFileSize());
    }
}
