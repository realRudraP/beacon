import { SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { AppSidebar } from "@/components/app-sidebar";
import { UserMenu } from "./UserMenu.";

export default function Dashboard() {
  return (
    <SidebarProvider>
      <div className="flex min-h-screen w-full">
        <AppSidebar />

        <main className="flex-1 p-6">
          <div className="flex items-center justify-between border-b pb-3 mb-4">
            <SidebarTrigger />
            <UserMenu />
          </div>

          <div className="flex items-center justify-center h-[calc(100vh-100px)]">
            <h1 className="text-4xl font-bold">Welcome to the Dashboard!</h1>
          </div>
        </main>
      </div>
    </SidebarProvider>
  );
}
