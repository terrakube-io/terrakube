import React, { Component, ErrorInfo, ReactNode, useEffect, useState } from "react";
import {
  SiDocker,
  SiGithub,
  SiGooglecloud,
  SiGrafana,
  SiKubernetes,
  SiOpenai,
  SiPrometheus,
  SiTerraform,
} from "react-icons/si";
import { VscAzure } from "react-icons/vsc";
import { antdIcons, getIcon } from "./config/iconList";
import axiosInstance from "./config/axiosConfig";
// Heavy deps (sucrase, antd, luxon, react-markdown, react-vis)
// are loaded lazily inside loadComponent() — they would otherwise dominate the main bundle.
// Compiled components are cached by action string so re-renders skip the transpile step entirely.
const compiledComponentCache = new Map<string, any>();
// react-icons are statically imported by name so the bundler tree-shakes them down to a
// handful of icons instead of pulling the entire react-icons/si set (~2500 icons / 5 MB).
const exposedReactIcons = {
  SiDocker,
  SiGithub,
  SiGooglecloud,
  SiGrafana,
  SiKubernetes,
  SiOpenai,
  SiPrometheus,
  SiTerraform,
  VscAzure,
} as const;

// List of antd components to consider for dynamic importing
const antdComponents = [
  "Affix",
  "Anchor",
  "AutoComplete",
  "Alert",
  "Avatar",
  "BackTop",
  "Badge",
  "Breadcrumb",
  "Button",
  "Calendar",
  "Card",
  "Collapse",
  "Carousel",
  "Cascader",
  "Checkbox",
  "Col",
  "ConfigProvider",
  "DatePicker",
  "Descriptions",
  "Divider",
  "Dropdown",
  "Drawer",
  "Empty",
  "Form",
  "Input",
  "InputNumber",
  "Layout",
  "List",
  "message",
  "Menu",
  "Mentions",
  "Modal",
  "Statistic",
  "notification",
  "PageHeader",
  "Pagination",
  "Popconfirm",
  "Popover",
  "Progress",
  "Radio",
  "Rate",
  "Result",
  "Row",
  "Select",
  "Skeleton",
  "Slider",
  "Space",
  "Spin",
  "Steps",
  "Switch",
  "Table",
  "Transfer",
  "Tree",
  "TreeSelect",
  "Tabs",
  "Tag",
  "TimePicker",
  "Timeline",
  "Tooltip",
  "Typography",
  "Upload",
];

const antdIconNames = Object.keys(antdIcons).filter((name) => name.endsWith("Outlined"));

const getRequiredAntdComponents = (componentString: string) =>
  antdComponents.filter((component) => componentString.includes(component));

const getRequiredAntdIcons = (componentString: string) =>
  antdIconNames.filter((icon) => componentString.includes(icon));

const pickAntdComponents = (mod: Record<string, unknown>, names: string[]) => {
  const out: Record<string, unknown> = {};
  for (const name of names) out[name] = mod[name];
  return out;
};

const resolveAntdIcons = (names: string[]) => {
  const out: Record<string, unknown> = {};
  for (const name of names) {
    out[name] = antdIcons[name as keyof typeof antdIcons] ?? getIcon(name);
  }
  return out;
};

const reactIconNames = Object.keys(exposedReactIcons);

const getRequiredReactIcons = (componentString: string) =>
  reactIconNames.filter((name) => componentString.includes(name));

const pickReactIcons = (names: string[]) => {
  const out: Record<string, unknown> = {};
  for (const name of names) {
    const icon = exposedReactIcons[name as keyof typeof exposedReactIcons];
    if (icon) out[name] = icon;
    else console.error(`Icon ${name} not exposed by ActionLoader`);
  }
  return out;
};

type Props = {
  children: ReactNode;
};

type State = {
  hasError: boolean;
};

class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(_: Error) {
    return { hasError: true };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error("ErrorBoundary caught an error", error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return <div>An error occurred while rendering this action.</div>;
    }

    return this.props.children;
  }
}

const ActionLoader = ({ action, context }: { action: any; context: any }) => {
  const [Component, setComponent] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    const loadComponent = async () => {
      try {
        const cached = compiledComponentCache.get(action);
        if (cached) {
          if (cancelled) return;
          setComponent(() => cached);
          setError(null);
          return;
        }

        const componentString = decodeURIComponent(escape(window.atob(action)));

        // All heavy deps loaded lazily so they stay out of the main bundle.
        const [{ transform }, antd, reactVis, ReactMarkdownModule, luxon] = await Promise.all([
          import("sucrase"),
          import("antd"),
          import("react-vis"),
          import("react-markdown"),
          import("luxon"),
        ]);
        await import("react-vis/dist/style.css");

        if (cancelled) return;

        const { DateTime } = luxon;
        const ReactMarkdown = (ReactMarkdownModule as any).default ?? ReactMarkdownModule;
        const antdAny = antd as any;
        const { Panel } = antdAny.Collapse;
        const { Paragraph, Text } = antdAny.Typography;
        const { RangePicker } = antdAny.DatePicker;
        const { Crosshair, Hint, HorizontalGridLines, LineSeries, VerticalGridLines, XAxis, XYPlot, YAxis } =
          reactVis as any;

        const requiredAntdComponents = getRequiredAntdComponents(componentString);
        const requiredAntdIcons = getRequiredAntdIcons(componentString);
        const requiredReactIcons = getRequiredReactIcons(componentString);

        const importedAntdComponents = pickAntdComponents(antd as Record<string, unknown>, requiredAntdComponents);
        const importedIcons = resolveAntdIcons(requiredAntdIcons);
        const importedReactIcons = pickReactIcons(requiredReactIcons);

        // Bind the component before transpiling. Sucrase prepends helper
        // functions when the action uses optional chaining or nullish
        // coalescing, so the transpiled output is no longer a single
        // expression and can't be wrapped in `return (...)` afterwards.
        const componentExpression = componentString.trim().replace(/;+\s*$/, "");
        const transpiledCode = transform(`const __ActionComponent = (${componentExpression}\n);`, {
          transforms: ["jsx"],
          production: true,
        }).code;

        const scopeContext: Record<string, any> = {
          React,
          useEffect,
          useState,
          Panel,
          Paragraph,
          Text,
          XYPlot,
          LineSeries,
          XAxis,
          YAxis,
          Hint,
          Crosshair,
          HorizontalGridLines,
          VerticalGridLines,
          axiosInstance,
          RangePicker,
          DateTime,
          ReactMarkdown,
          ...(antd as Record<string, unknown>),
          ...importedAntdComponents,
          ...importedIcons,
          ...importedReactIcons,
        };

        const functionParams = Object.keys(scopeContext);
        const functionArgs = functionParams.map((key) => scopeContext[key]);

        const createComponent = new Function(...functionParams, `${transpiledCode}\nreturn __ActionComponent;`);
        const component = createComponent(...functionArgs);

        compiledComponentCache.set(action, component);

        if (cancelled) return;
        setComponent(() => component);
        setError(null);
      } catch (err) {
        if (cancelled) return;
        console.error("Error creating component:", err);
        setError(`Error: ${err instanceof Error ? err.message : String(err)}`);
        setComponent(() => () => <div>Error loading component</div>);
      }
    };

    loadComponent();
    return () => {
      cancelled = true;
    };
  }, [action]);

  if (error) {
    return <div className="error-message">Error loading component: {error}</div>;
  }

  if (!Component) {
    return <div>Loading...</div>;
  }

  return (
    <ErrorBoundary>
      <Component context={context} />
    </ErrorBoundary>
  );
};

export default ActionLoader;
