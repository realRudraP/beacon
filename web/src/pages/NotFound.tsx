export default function NotFound() {
  return (
    <div className="flex h-screen items-center justify-center bg-background">
      <div className="text-center">
        <h1 className="text-6xl font-bold">404</h1>
        <p className="mt-4 text-lg">
          We looked everywhere, but couldn't find what you were looking for.
        </p>
        <button
          onClick={() => window.history.back()}
          className="mt-6 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        >
          Go Back
        </button>
      </div>
    </div>
  );
}
