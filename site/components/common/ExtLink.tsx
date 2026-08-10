import Link from 'next/link'

export default function ExtLink({
  href,
  children,
  ...props
}: {
  href: string
  children: React.ReactNode
} & React.AnchorHTMLAttributes<HTMLAnchorElement>) {
  return (
    <a href={href} rel="noopener" target="_blank" {...props}>
      {children}
    </a>
  )
}
