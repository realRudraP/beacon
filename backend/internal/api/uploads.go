package api

import (
	"io"
	"net/http"
	"os"
	"path/filepath"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

const MAX_UPLOAD_SIZE = 5 * 1024 * 1024 // 10 MB

func (s *Server) HandleUploadAttendancePhoto(w http.ResponseWriter, r *http.Request) {
	attendanceIDStr := chi.URLParam(r, "id")
	attendanceID, err := uuid.Parse(attendanceIDStr)
	if err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid attendance id",
		})
		return
	}

	photoType := r.URL.Query().Get("type")
	if photoType != "in" && photoType != "out" {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid photo type, must be 'in' or 'out'",
		})
		return
	}

	// Verify ownership
	userID := r.Context().Value("authClaims").(*token.Claims).UserID
	record, err := s.store.GetAttendanceRecordByID(r.Context(), attendanceID)
	if err != nil {
		s.writeAPIError(w, http.StatusNotFound, APIError{Message: "attendance record not found"})
		return
	}
	if record.UserID != userID {
		s.writeAPIError(w, http.StatusForbidden, APIError{Message: "forbidden"})
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, MAX_UPLOAD_SIZE)
	if err := r.ParseMultipartForm(MAX_UPLOAD_SIZE); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{Message: "file too large, max 5MB"})
		return
	}

	file, _, err := r.FormFile("photo")
	if err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{Message: "failed to read file"})
		return
	}
	defer file.Close()

	// Detect content type from first 512 bytes
	buff := make([]byte, 512)
	if _, err = file.Read(buff); err != nil {
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Message: "failed to read file"})
		return
	}
	filetype := http.DetectContentType(buff)
	if filetype != "image/jpeg" {
		s.writeAPIError(w, http.StatusBadRequest, APIError{Message: "invalid file type, only JPEG is allowed"})
		return
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Message: "failed to seek file"})
		return
	}

	newFileName := attendanceID.String() + "_" + photoType + ".jpg"
	uploadDir := "./data/uploads/photos"
	if err := os.MkdirAll(uploadDir, os.ModePerm); err != nil {
		s.logger.Error("failed to create upload directory", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Message: "internal server error"})
		return
	}

	dst, err := os.Create(filepath.Join(uploadDir, newFileName))
	if err != nil {
		s.logger.Error("failed to create destination file", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Message: "internal server error"})
		return
	}
	defer dst.Close()

	if _, err := io.Copy(dst, file); err != nil {
		s.logger.Error("failed to save file", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Message: "internal server error"})
		return
	}

	photoURL := "/static/photos/" + newFileName
	if err = s.store.UpdateAttendancePhoto(r.Context(), database.UpdateAttendancePhotoParams{
		ID:        attendanceID,
		PhotoType: photoType,
		PhotoUri:  photoURL,
	}); err != nil {
		s.logger.Error("failed to update attendance photo", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Message: "internal server error"})
		return
	}

	s.writeJSON(w, http.StatusOK, map[string]string{
		"photo_url": photoURL,
	})
}
