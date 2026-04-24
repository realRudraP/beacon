import { create } from "zustand";
import { persist } from "zustand/middleware";
import api from "@/api/axiosClient";

type User = {
  username: string | null;
  full_name: string | null;
  phone_number: string | null;
  role: string | null;
  active: boolean;
};

type UserStore = {
  user: User | null;
  loading: boolean;
  fetchUser: () => Promise<void>;
};

export const useUserStore = create<UserStore>((set, get) => ({
  user: null,
  loading: true,

  fetchUser: async () => {
    const state = get();
    if (state.user || !state.loading) return;

    try {
      const res = await api.get("/users/me");
      set({ user: res.data, loading: false });
    } catch {
      set({ user: null, loading: false });
    }
  },
}));
