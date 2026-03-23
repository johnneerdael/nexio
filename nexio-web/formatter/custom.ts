import { BaseFormatter, type FormatterConfig, type FormatterContext } from './base.js';
import { FULL_LANGUAGE_MAPPING } from './languages.js';

export class CustomFormatter extends BaseFormatter {
  constructor(
    nameTemplate: string,
    descriptionTemplate: string,
    ctx: FormatterContext
  ) {
    super(
      {
        name: nameTemplate,
        description: descriptionTemplate,
      },
      ctx
    );
  }

  public static fromConfig(
    config: FormatterConfig,
    ctx: FormatterContext
  ): CustomFormatter {
    return new CustomFormatter(config.name, config.description, ctx);
  }

  public updateTemplate(
    nameTemplate: string,
    descriptionTemplate: string
  ): void {
    this.config = {
      name: nameTemplate,
      description: descriptionTemplate,
    };
  }

  public getTemplate(): FormatterConfig {
    return this.config;
  }
}
