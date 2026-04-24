import axios from "axios";
import { useAuthStore } from "@/store/authStore";

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
});

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers["Authorization"] = `Bearer ${token}`;
  }
  return config;
});

let isRefreshing = false;

api.interceptors.response.use(
  (response) => response,

  async (error) => {
    const originalRequest = error.config;

    if (error.response?.status === 401 && !originalRequest._retry) {
      // If the token is already being refreshed, reject the request
      if (isRefreshing) return Promise.reject(error);

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const { refreshToken, setTokens, clearTokens } =
          useAuthStore.getState();

        const { data } = axios.post(
          `${import.meta.env.VITE_API_BASE_URL}/auth/refresh`,
          {
            refresh_token: refreshToken,
            device_info: window.navigator.userAgent,
          },
        );

        const { access_token, refresh_token: newRefreshToken } = data;
        setTokens(access_token, newRefreshToken ?? refreshToken);

        originalRequest.headers.Authorization = `Bearer ${access_token}`;
        return api(originalRequest);
      } catch (err) {
        useAuthStore.getState().clearTokens();
        window.location.href = "/login";
        return Promise.reject(err);
      } finally {
        isRefreshing = false;
      }
    }
    return Promise.reject(error);
  },
);

export default api;
