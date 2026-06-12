import { create } from "zustand";
import api from "@/api/axiosClient";
import { useAuthStore } from "@/store/authStore";

type User = {
  username: string | null;
  full_name: string | null;
  phone_number: string | null;
  role: string | null;
  email: string | null;
  active: boolean;
};

type UserStore = {
  user: User | null;
  loading: boolean;
  fetchUser: () => Promise<void>;
  clearUser: () => void;
};

export const useUserStore = create<UserStore>((set, get) => ({
  user: null,
  loading: !!useAuthStore.getState().accessToken,
  fetchUser: async () => {
    if (get().user) return;
    set({ loading: true });
    try {
      const res = await api.get("/users/me");
      set({ user: res.data, loading: false });
    } catch {
      set({ user: null, loading: false });
    }
  },
  clearUser: () => set({ user: null, loading: false }),
}));
