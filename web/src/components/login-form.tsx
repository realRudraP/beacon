import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";

import { useForm } from "react-hook-form";
import { z } from "zod";
import { zodResolver } from "@hookform/resolvers/zod";

// 1. Define the shape + validation rules with Zod
const loginSchema = z.object({
  username: z.string().min(1, "Username is required"),
  password: z.string().min(6, "Password must be at least 6 characters"),
  device_info: z.string().optional(),
});

// Export the type so the parent page can use it
export type LoginFormData = z.infer<typeof loginSchema>;

interface LoginFormProps extends Omit<
  React.ComponentProps<"form">,
  "onSubmit"
> {
  onSubmitAction: (data: LoginFormData) => Promise<void>;
  serverError?: string | null;
}

export function LoginForm({
  className,
  onSubmitAction,
  serverError,
  ...props
}: LoginFormProps) {
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
  });

  return (
    <form
      className={cn("flex flex-col gap-6", className)}
      onSubmit={handleSubmit(onSubmitAction)}
      {...props}
    >
      <FieldGroup>
        <div className="flex flex-col items-center gap-1 text-center">
          <h1 className="text-2xl font-bold">Login to your account</h1>
          <p className="text-sm text-balance text-muted-foreground">
            Enter your credentials below to access your account
          </p>
        </div>

        {serverError && (
          <div className="p-3 text-sm font-medium text-destructive bg-destructive/10 rounded-md text-center">
            {serverError}
          </div>
        )}

        <Field>
          <FieldLabel htmlFor="username">Username</FieldLabel>
          <Input
            id="username"
            type="text"
            className={cn(
              "bg-background",
              errors.username && "border-destructive",
            )}
            {...register("username")}
          />
          {errors.username && (
            <p className="text-sm text-destructive">
              {errors.username.message}
            </p>
          )}
        </Field>

        <Field>
          <div className="flex items-center">
            <FieldLabel htmlFor="password">Password</FieldLabel>
          </div>
          <Input
            id="password"
            type="password"
            className={cn(
              "bg-background",
              errors.password && "border-destructive",
            )}
            {...register("password")}
          />
          {errors.password && (
            <p className="text-sm text-destructive">
              {errors.password.message}
            </p>
          )}
        </Field>

        <Field>
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? "Logging in..." : "Login"}
          </Button>
        </Field>
      </FieldGroup>
    </form>
  );
}
