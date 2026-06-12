import React, { createContext, useContext, useEffect, useState } from "react";
import api from "@/api/axiosClient";

interface SystemState {
  needsSetup: boolean | null;
  branding: { business_name: string; logo_url: string };
  features: { auto_delete_days: number; require_worker_pins: boolean };
}

const defaultState: SystemState = {
  needsSetup: null,
  branding: { business_name: "Beacon", logo_url: "" },
  features: { auto_delete_days: 30, require_worker_pins: false },
};

const ConfigContext = createContext<SystemState>(defaultState);

export const ConfigProvider = ({ children }: { children: React.ReactNode }) => {
  const [systemState, setSystemState] = useState<SystemState>(defaultState);

  useEffect(() => {
    const bootstrapSystem = async () => {
      try {
        const { data } = await api.get("/system/config");
        setSystemState({
          needsSetup: data.setup_required,
          branding: data.branding,
          features: data.features,
        });
      } catch (error) {
        console.error("Critical: Failed to bootstrap system", error);
        // Fail closed: Assume it's set up so we don't expose the admin creation screen
        setSystemState((prev) => ({ ...prev, needsSetup: false }));
      }
    };

    bootstrapSystem();
  }, []);

  // Block the entire app from rendering until we have our boot payload
  if (systemState.needsSetup === null) {
    return (
      <div className="flex h-screen items-center justify-center text-muted-foreground bg-background">
        Booting system...
      </div>
    );
  }

  return (
    <ConfigContext.Provider value={systemState}>
      {children}
    </ConfigContext.Provider>
  );
};

export const useSystemState = () => useContext(ConfigContext);
