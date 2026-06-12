export default function Unauthorized() {
  return (
    <div className="flex h-screen w-full flex-col items-center justify-center gap-4 bg-muted">
      <h1 className="text-2xl font-semibold">Unauthorized Access</h1>
      <p className="text-center text-sm text-muted-foreground">
        You do not have permission to access this page. Please log in with the
        appropriate credentials.
      </p>
      <a
        href="/login"
        className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
      >
        Go to Login
      </a>
    </div>
  );
}
