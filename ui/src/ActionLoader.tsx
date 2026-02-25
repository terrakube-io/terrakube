import { antdIcons, getIcon } from "./config/iconList";
import { transform } from "@babel/standalone";
import { Collapse, DatePicker, Typography } from "antd";
import { DateTime } from "luxon";
import React, { Component, ErrorInfo, ReactNode, useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import { Crosshair, Hint, HorizontalGridLines, LineSeries, VerticalGridLines, XAxis, XYPlot, YAxis } from "react-vis";
import "react-vis/dist/style.css";
import axiosInstance from "./config/axiosConfig";

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

const { Panel } = Collapse;
const { Paragraph, Text } = Typography;
const { RangePicker } = DatePicker;
// List of antd icons to consider for dynamic importing
const antdIconNames = Object.keys(antdIcons).filter((name) => name.endsWith("Outlined"));

// Function to identify required antd components
const getRequiredAntdComponents = (componentString: string) => {
  return antdComponents.filter((component) => componentString.includes(component));
};

// Function to identify required antd icons
const getRequiredAntdIcons = (componentString: string) => {
  return antdIconNames.filter((icon) => componentString.includes(icon));
};

// Function to dynamically import antd components
const importAntdComponents = async (components: string[]) => {
  // Import the entire antd library instead of individual components
  try {
    const antd = await import("antd");

    const importedComponents: Record<string, any> = {};
    components.forEach((component: string) => {
      // Access the component directly from the antd object
      importedComponents[component] = antd[component as keyof typeof antd];
    });
    return importedComponents;
  } catch (error) {
    console.error(`Error importing antd components:`, error);
    return {};
  }
};

// Function to dynamically import antd icons
const importAntdIcons = async (icons: any) => {
  const importedIcons: Record<string, any> = {};

  icons.forEach((icon: string) => {
    if (antdIcons[icon as keyof typeof antdIcons]) {
      importedIcons[icon] = antdIcons[icon as keyof typeof antdIcons];
    } else {
      importedIcons[icon] = getIcon(icon);
    }
  });

  return importedIcons;
};

// List of react-icons to consider for dynamic importing
const reactIcons = [
  "VscAzure",
  "SiAmazonaws",
  "SiGithub",
  "SiGrafana",
  "SiKubernetes",
  "SiTerraform",
  "SiDocker",
  "SiPrometheus",
  "SiGooglecloud",
  "SiOpenai",
];

// Function to identify required react-icons components
const getRequiredReactIcons = (componentString: string) => {
  return reactIcons.filter((icon) => componentString.includes(icon));
};

// Function to dynamically import react-icons components
const importReactIcons = async (icons: string[]) => {
  if (icons.length === 0) return {};

  try {
    const siModule = await import("react-icons/si");
    const typedSiModule = siModule as Record<string, any>;
    const importedIcons: Record<string, any> = {};

    icons.forEach((icon: string) => {
      if (typedSiModule[icon]) {
        importedIcons[icon] = typedSiModule[icon];
      } else {
        console.error(`Icon ${icon} not found in react-icons/si`);
      }
    });

    return importedIcons;
  } catch (error) {
    console.error("Error importing react-icons:", error);
    return {};
  }
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
    const loadComponent = async () => {
      try {
        const componentString = decodeURIComponent(escape(window.atob(action)));
        console.debug("Decoded Component String:", componentString);

        // Import entire antd library upfront
        const antd = await import("antd");

        const requiredAntdComponents = getRequiredAntdComponents(componentString);
        const requiredAntdIcons = getRequiredAntdIcons(componentString);
        const requiredReactIcons = getRequiredReactIcons(componentString);

        console.debug("Required Antd Components:", requiredAntdComponents);
        console.debug("Required Antd Icons:", requiredAntdIcons);
        console.debug("Required React Icons:", requiredReactIcons);

        const [, importedIcons, importedReactIcons] = await Promise.all([
          importAntdComponents(requiredAntdComponents),
          importAntdIcons(requiredAntdIcons),
          importReactIcons(requiredReactIcons),
        ]);

        // Transform JSX to JavaScript
        let transpiledCode = transform(componentString, {
          presets: ["react"],
        }).code;
        console.debug("Transpiled Code:", transpiledCode);

        // Remove the last semicolon
        const lastSemicolonIndex = transpiledCode!.lastIndexOf(";");
        if (lastSemicolonIndex !== -1) {
          transpiledCode = transpiledCode!.slice(0, lastSemicolonIndex);
        }

        // Create a context with all required components from antd
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
          // Add all of antd components to the context
          ...antd,
        };

        // Add the imported icons
        for (const icon of requiredAntdIcons) {
          if (importedIcons[icon]) {
            scopeContext[icon] = importedIcons[icon];
          }
        }

        // Add the imported react-icons
        for (const icon of requiredReactIcons) {
          if (importedReactIcons[icon]) {
            scopeContext[icon] = importedReactIcons[icon];
          }
        }

        // Create the component function with the entire context
        const functionParams = Object.keys(scopeContext);
        const functionArgs = functionParams.map((key) => scopeContext[key]);

        const createComponent = new Function(...functionParams, `return (${transpiledCode})`);

        const component = createComponent(...functionArgs);
        console.debug("Component Function:", component);

        setComponent(() => component);
        setError(null);
      } catch (error) {
        console.error("Error creating component:", error);
        setError(`Error: ${error instanceof Error ? error.message : String(error)}`);
        setComponent(() => () => <div>Error loading component</div>);
      }
    };

    loadComponent();
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
