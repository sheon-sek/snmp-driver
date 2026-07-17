import { createContext, useContext, type ReactNode } from "react"
import { Toaster as Sonner } from "sonner"

type ToasterProps = React.ComponentProps<typeof Sonner>
const ToasterIdContext = createContext<string | null>(null)

function ToasterIdProvider({
  children,
  value,
}: {
  children: ReactNode
  value: string
}) {
  return (
    <ToasterIdContext.Provider value={value}>
      {children}
    </ToasterIdContext.Provider>
  )
}

function useToasterId() {
  const toasterId = useContext(ToasterIdContext)
  if (!toasterId) {
    throw new Error("ToasterIdProvider is missing")
  }
  return toasterId
}

const Toaster = ({ theme = "system", ...props }: ToasterProps) => {
  return (
    <Sonner
      theme={theme}
      className="toaster group"
      toastOptions={{
        classNames: {
          toast:
            "group toast group-[.toaster]:bg-background group-[.toaster]:text-foreground group-[.toaster]:border-border group-[.toaster]:shadow-lg",
          description: "group-[.toast]:text-muted-foreground",
          actionButton:
            "group-[.toast]:bg-primary group-[.toast]:text-primary-foreground",
          cancelButton:
            "group-[.toast]:bg-muted group-[.toast]:text-muted-foreground",
        },
      }}
      {...props}
    />
  )
}

export { Toaster, ToasterIdProvider, useToasterId, type ToasterProps }
