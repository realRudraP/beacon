import {  SidebarTrigger } from "@/components/ui/sidebar";
import { UserMenu } from "./UserMenu";
import { useSystemState } from "@/context/ConfigContext";

export default function Dashboard() {
  const config = useSystemState();
  return (
   <>
          <div className="flex items-center justify-between border-b pb-3 mb-4">
            <div className="flex items-center gap-4">
              <SidebarTrigger />
              <span className="font-semibold text-lg tracking-tight">
                {config.branding.business_name}
              </span>
            </div>
            <UserMenu />
          </div>

          <div className="flex items-center justify-center h-[calc(100vh-100px)]">
            <h1 className="text-4xl font-bold">Welcome to the Dashboard!</h1>
          </div>
   </>
  );
}
