import { NavLink } from 'react-router-dom'
import { Sheet, SheetContent, SheetTrigger } from '@/components/ui/sheet'
import { useAuth } from '@/hooks/useAuth.ts'
import { Button } from '@/components/ui/button.tsx'
import { Menu } from 'lucide-react'

const navLinks = [
  { to: '/library', label: 'Library' },
  { to: '/upload', label: 'Upload' },
]

const linkClass = ({ isActive }: { isActive: boolean }) =>
  isActive
    ? 'text-primary font-semibold'
    : 'text-muted-foreground hover:text-foreground transition-colors'

export default function Header() {
  const { user, isAuthenticated, logout } = useAuth()

  return (
    <header className="sticky top-0 z-50 h-16 border-b bg-background/95 backdrop-blur">
      <div className="container mx-auto flex h-full items-center justify-between px-4">
        {/* Logo */}
        <NavLink to="/" className="text-xl font-bold text-primary">
          Sublio
        </NavLink>

        {/* Desktop nav */}
        <nav className="hidden md:flex items-center gap-6">
          {navLinks.map(({ to, label }) => (
            <NavLink key={to} to={to} className={linkClass}>
              {label}
            </NavLink>
          ))}
        </nav>

        {/* Auth — desktop */}
        <div className="hidden md:flex items-center gap-3">
          {isAuthenticated ? (
            <>
              <span className="text-sm text-muted-foreground">{user?.email}</span>
              <Button variant="outline" size="sm" onClick={logout}>
                Logout
              </Button>
            </>
          ) : (
            <Button asChild size="sm">
              <NavLink to="/login">Login</NavLink>
            </Button>
          )}
        </div>

        {/* Mobile hamburger */}
        <Sheet>
          <SheetTrigger asChild>
            <Button variant="ghost" size="icon" className="md:hidden">
              <Menu className="h-5 w-5" />
            </Button>
          </SheetTrigger>
          <SheetContent side="left">
            <nav className="flex flex-col gap-4 mt-6">
              {navLinks.map(({ to, label }) => (
                <NavLink key={to} to={to} className={linkClass}>
                  {label}
                </NavLink>
              ))}
              <div className="mt-4 border-t pt-4">
                {isAuthenticated ? (
                  <>
                    <p className="text-sm text-muted-foreground mb-3">{user?.email}</p>
                    <Button variant="outline" size="sm" className="w-full" onClick={logout}>
                      Logout
                    </Button>
                  </>
                ) : (
                  <Button asChild size="sm" className="w-full">
                    <NavLink to="/login">Login</NavLink>
                  </Button>
                )}
              </div>
            </nav>
          </SheetContent>
        </Sheet>
      </div>
    </header>
  )
}
