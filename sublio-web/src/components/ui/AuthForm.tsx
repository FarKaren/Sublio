import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { type Control, type FieldPath, useForm } from 'react-hook-form'
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
} from '@/components/ui/form.tsx'

import { Button } from '@/components/ui/button.tsx'
import { Input } from '@/components/ui/input.tsx'
import { Loader2 } from 'lucide-react'
import React from 'react'
import type { User } from '@/types'
import type { UseMutationResult } from '@tanstack/react-query'
import { authSchema } from '@/utils/zod.ts'

interface AuthFormProps {
  mutation: UseMutationResult<
    { user: User; accessToken: string },
    Error,
    z.infer<typeof authSchema>
  >
}

const AuthForm = ({ mutation }: AuthFormProps) => {
  const form = useForm<z.infer<typeof authSchema>>({
    resolver: zodResolver(authSchema),
    defaultValues: {
      email: '',
      password: '',
    },
  })

  const onSubmit = (values: z.infer<typeof authSchema>) => {
    mutation.mutate(values)
  }

  return (
    <Form {...form}>
      <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4 mt-4" noValidate>
        <AuthFormField
          name="email"
          label="Email"
          placeholder="Email"
          inputType="email"
          formControl={form.control}
        />

        <AuthFormField
          name="password"
          label="Password"
          placeholder="Password"
          description="At least 6 characters"
          inputType="password"
          formControl={form.control}
        />
        {mutation.isError && <p className="text-sm text-destructive">Invalid email or password</p>}
        <Button
          type="submit"
          disabled={mutation.isPending}
          className="w-full [background:linear-gradient(to_right,var(--color-primary),var(--color-accent))] text-white hover:opacity-90 transition-opacity"
        >
          {mutation.isPending ? <Loader2 className="animate-spin" /> : 'Submit'}
        </Button>
      </form>
    </Form>
  )
}

interface AuthFormFieldProps {
  name: FieldPath<z.infer<typeof authSchema>>
  label: string
  placeholder: string
  description?: string
  inputType?: string
  formControl: Control<z.infer<typeof authSchema>>
}

const AuthFormField: React.FC<AuthFormFieldProps> = ({
  name,
  label,
  placeholder,
  description,
  inputType,
  formControl,
}) => {
  return (
    <FormField
      control={formControl}
      name={name}
      render={({ field, fieldState }) => (
        <FormItem>
          <FormLabel className={fieldState.error ? 'text-destructive' : ''}>{label}</FormLabel>
          <FormControl>
            <Input placeholder={placeholder} type={inputType || 'text'} {...field} />
          </FormControl>
          {description && <FormDescription>{description}</FormDescription>}
          {fieldState.error && (
            <p className="text-sm font-medium text-destructive">{fieldState.error.message}</p>
          )}
        </FormItem>
      )}
    />
  )
}

export default AuthForm
