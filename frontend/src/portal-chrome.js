import { createContext, useContext, useEffect } from "react";

/**
 * Lets a routed page (currently App.jsx / Browse) render its own controls
 * into the portal's single top bar, instead of stacking a second fixed
 * navbar on top of the shell.
 *
 * Kept in its own module so App.jsx and UserPortal.jsx can both import it
 * without a circular dependency (UserPortal lazy-imports App).
 */
export const PortalChromeContext = createContext(null);

export function usePortalChrome(render, deps) {
  const chrome = useContext(PortalChromeContext);
  const setChrome = chrome?.setChrome;

  useEffect(() => {
    if (!setChrome) return undefined;
    setChrome(render());
    return () => setChrome(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setChrome, ...deps]);
}
